package pl.sroki.cci.android.ui.catalog.caps.detail

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.PurchasedCapsLocalStore
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.data.datasource.remote.firestore.ProducerSelection
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.di.ApplicationScope
import pl.sroki.cci.android.model.BinderSuggestion
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.Producer
import pl.sroki.cci.android.model.toSnapshot
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import pl.sroki.cci.android.model.binder.CatalogStatus
import pl.sroki.cci.android.model.binder.POSITIONS_PER_PAGE

enum class CapStatus { IN_COLLECTION, PURCHASED, MISSING }

sealed interface CapDetailUiState {
    data class Success(
        val cap: CapExtended,
        val status: CapStatus,
        val binderInfo: CapBinderInfo?
    ) : CapDetailUiState
    object Error : CapDetailUiState
    object Loading : CapDetailUiState
}

@HiltViewModel
class CapDetailViewModel @Inject constructor(
    private val repository: CapsRepository,
    private val capPositionRepository: CapPositionRepository,
    private val capCacheRepository: CapCacheRepository,
    private val sessionRepository: SessionRepository,
    private val binderRepository: BinderRepository,
    private val binderPageRepository: BinderPageRepository,
    private val purchasedCapsLocalStore: PurchasedCapsLocalStore,
    // Zapisy kolekcji idą tędy, a nie przez viewModelScope — patrz komentarz w setStatus.
    @ApplicationScope private val externalScope: CoroutineScope
) : ViewModel() {

    var capDetailUiState: CapDetailUiState by mutableStateOf(CapDetailUiState.Loading)
        private set

    val isLoggedIn: StateFlow<Boolean> = sessionRepository.isLoggedIn

    var binders: List<BinderView> by mutableStateOf(emptyList())
        private set
    var binderPages: List<BinderPageView> by mutableStateOf(emptyList())
        private set
    var selectedBinderId: Long? by mutableStateOf(null)
        private set
    var selectedPageId: Long? by mutableStateOf(null)
        private set
    var selectedPosition: Int? by mutableStateOf(null)
        private set
    var isSaving: Boolean by mutableStateOf(false)
        private set
    var assignmentError: String? by mutableStateOf(null)
        private set
    var binderSuggestion: BinderSuggestion? by mutableStateOf(null)
        private set

    // Status rozjazdu kapsla (null/"ok"/"unknown" = bez banera; missing/swapped/updated = baner z akcjami).
    var catalogStatus: CatalogStatus? by mutableStateOf(null)
        private set

    // Co dokładnie się zmieniło (label -> "przed → po"), do wyświetlenia w banerze rozjazdu.
    var catalogChanges: List<Pair<String, String>> by mutableStateOf(emptyList())
        private set

    private var pagesJob: Job? = null
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            binderRepository.getAll().collect { binders = it }
        }
    }

    fun onBinderSelected(binderId: Long) {
        selectedBinderId = binderId
        selectedPageId = null
        selectedPosition = null
        binderPages = emptyList()
        pagesJob?.cancel()
        pagesJob = viewModelScope.launch {
            binderPageRepository.getByBinder(binderId).collect { binderPages = it }
        }
    }

    fun onPageSelected(pageId: Long) {
        selectedPageId = pageId
        selectedPosition = null
    }

    fun onPositionSelected(position: Int) {
        selectedPosition = position
        saveAssignment()
    }

    fun dismissError() { assignmentError = null }

    /** Rozjazd: zachowaj mój snapshot — tylko oznacz jako rozstrzygnięty. */
    fun keepSnapshot() {
        val capId = (capDetailUiState as? CapDetailUiState.Success)?.cap?.id?.toLong() ?: return
        viewModelScope.launch {
            capCacheRepository.markVerified(capId, CatalogStatus.OK, System.currentTimeMillis())
            catalogStatus = CatalogStatus.OK
            catalogChanges = emptyList()
        }
    }

    /** Rozjazd: zaakceptuj świeże dane z katalogu — nadpisz snapshot (Room + Firestore). */
    fun acceptNew() {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            val s = current.cap.toSnapshot()
            // Akceptacja stanu katalogu przy PRODUCER_REMOVED musi PORZUCIĆ ręczny wybór:
            // inaczej selected_producer_id nadal wskazuje producenta, którego katalog nie zna,
            // i następna weryfikacja stawia ten sam baner od nowa.
            if (catalogStatus == CatalogStatus.PRODUCER_REMOVED) {
                capCacheRepository.clearProducerSelection(capId, s.country)
                // Bez tego pole capSelectedProducerId zostawało w Firestore i po odtworzeniu
                // na nowym urządzeniu wskrzeszało usuniętego producenta — baner PRODUCER_REMOVED
                // wracał w nieskończonej pętli.
                runCatching { capPositionRepository.clearProducerSelection(capId) }.onFailure {
                    Log.w("CCI_SYNC", "czyszczenie wyboru producenta dla kapsla $capId nie trafiło do Firestore", it)
                    Sentry.captureException(it)
                }
            }
            capCacheRepository.upsertSnapshot(
                capId, s.name, s.country, s.imageUrl, s.createdAt, s.createdById, s.updatedAt
            )
            capCacheRepository.markVerified(capId, CatalogStatus.OK, System.currentTimeMillis())
            // Snapshot w Firestore musi nadążyć za tym w Roomie, a cicha porażka cofa nas
            // dokładnie do stanu sprzed poprawki, tyle że bez śladu, że tak się stało.
            runCatching { capPositionRepository.updateSnapshot(capId, s) }.onFailure {
                Log.w("CCI_SYNC", "nowy snapshot kapsla $capId nie trafił do Firestore", it)
                Sentry.captureException(it)
            }
            catalogStatus = CatalogStatus.OK
            catalogChanges = emptyList()
        }
    }

    /** Ręczny wybór producenta dla kapsla "-Multiple countries" — nadpisuje kraj/producenta wszędzie. */
    fun selectProducer(producer: Producer) {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            capCacheRepository.selectProducer(capId, producer.id, producer.name, producer.country.name)
            capCacheRepository.markVerified(capId, CatalogStatus.OK, System.currentTimeMillis())
            // Utrwal wybór w Firestore — bez tego żyje tylko w Roomie i ginie przy reinstalacji.
            // Nieudane wypchnięcie musi zostawić ślad: cicha porażka cofa nas dokładnie do stanu
            // sprzed tej poprawki, tyle że bez żadnej informacji, że tak się stało.
            runCatching {
                capPositionRepository.updateProducerSelection(
                    capId,
                    ProducerSelection(producer.id, producer.name, producer.country.name)
                )
            }.onFailure {
                Log.w("CCI_SYNC", "wybór producenta dla kapsla $capId nie trafił do Firestore", it)
                Sentry.captureException(it)
            }
            catalogStatus = CatalogStatus.OK
            catalogChanges = emptyList()
            capDetailUiState = current.copy(cap = current.cap.copy(country = producer.country))
        }
    }

    /** Rozjazd: odepnij kapsel z klasera — zostaje w kolekcji, wraca na listę Zakupione. */
    fun unlinkFlagged() {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            try {
                capPositionRepository.unassignToPurchased(capId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                assignmentError = "Nie udało się odpiąć kapsla"
                return@launch
            }
            // Rozjazd rozstrzygnięty odpięciem. Bez tego zapisu status zostawał w Roomie
            // i baner wracał przy każdym kolejnym wejściu w szczegóły.
            capCacheRepository.markVerified(capId, CatalogStatus.OK, System.currentTimeMillis())
            catalogStatus = null
            catalogChanges = emptyList()
            capDetailUiState = current.copy(status = CapStatus.PURCHASED, binderInfo = null)
        }
    }

    /**
     * Różnica pole-po-polu między zapisanym snapshotem a świeżym stanem z API — ta sama logika
     * porównania co w CollectionVerifier.verify(), ale zwrócona jako lista do wyświetlenia
     * użytkownikowi zamiast tylko statusu.
     */
    private fun computeChanges(stored: CachedCap, cap: CapExtended): List<Pair<String, String>> {
        val freshName = cap.description ?: ""
        val freshUpdatedAt = cap.updatedAt?.toString()
        val matchedProducer = stored.selectedProducerId?.let { id -> cap.producers.firstOrNull { it.id == id } }
        val freshCountry = matchedProducer?.country?.name ?: cap.country.name

        val changes = mutableListOf<Pair<String, String>>()
        if (stored.name.isNotBlank() && stored.name != freshName) {
            changes += "Tekst" to "${stored.name} → $freshName"
        }
        if (stored.country.isNotBlank() && stored.country != freshCountry) {
            changes += "Kraj" to "${stored.country} → $freshCountry"
        }
        if (matchedProducer != null && stored.producer.isNotBlank() && stored.producer != matchedProducer.name) {
            changes += "Producent" to "${stored.producer} → ${matchedProducer.name}"
        }
        if (stored.imageUrl.isNotBlank() && stored.imageUrl != cap.imageUrl) {
            changes += "Zdjęcie" to "zmienione"
        }
        if (stored.updatedAt != null && stored.updatedAt != freshUpdatedAt) {
            changes += "Data edycji w katalogu" to "zaktualizowana"
        }
        return changes
    }

    private fun saveAssignment() {
        val pageId = selectedPageId ?: return
        val position = selectedPosition ?: return
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            isSaving = true
            assignmentError = null
            try {
                // externalScope z tego samego powodu co w setStatus: przypisanie zaczyna się od
                // dopisania kapsla do kolekcji, więc przy wygasłej sesji czeka na całą ścieżkę
                // odzyskiwania. Wyjście z ekranu w tym oknie zostawiłoby snapshot w Roomie bez
                // pozycji w klaserze — albo pozycję bez kapsla po stronie serwera.
                externalScope.async {
                    val snapshot = current.cap.toSnapshot()
                    capCacheRepository.upsertSnapshot(
                        capId, snapshot.name, snapshot.country, snapshot.imageUrl,
                        snapshot.createdAt, snapshot.createdById, snapshot.updatedAt
                    )
                    if (current.status == CapStatus.IN_COLLECTION) {
                        capPositionRepository.reassign(capId, pageId, position, snapshot)
                    } else {
                        if (!current.cap.isInCollection) {
                            repository.addToCollection(current.cap.id)
                        }
                        capPositionRepository.assign(pageId, position, capId, snapshot)
                    }
                }.await()
                val newBinderInfo = capPositionRepository.getBinderInfoByCapId(capId)
                capDetailUiState = current.copy(
                    status = CapStatus.IN_COLLECTION,
                    binderInfo = newBinderInfo,
                    cap = current.cap.copy(isInCollection = true)
                )
                binderSuggestion = null
            } catch (e: CancellationException) {
                // Ekran zniknął w trakcie — zapis leci dalej w externalScope, nie ma czego cofać.
                throw e
            } catch (e: IllegalStateException) {
                assignmentError = e.message ?: "Nie udało się przypisać kapsla"
                selectedPosition = null
            } catch (e: Exception) {
                assignmentError = "Nie udało się przypisać kapsla"
                selectedPosition = null
            } finally {
                isSaving = false
            }
        }
    }

    fun setStatus(status: CapStatus) {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val leavingCollection = current.status == CapStatus.IN_COLLECTION
        capDetailUiState = current.copy(
            status = status,
            binderInfo = null,
            cap = current.cap.copy(isInCollection = status != CapStatus.MISSING)
        )
        if (leavingCollection) {
            selectedBinderId = null
            selectedPageId = null
            selectedPosition = null
            binderPages = emptyList()
            pagesJob?.cancel()
        }
        if (status == CapStatus.PURCHASED) {
            launchSuggestion(current.cap.country.name, current.cap.id.toLong())
        } else {
            suggestionJob?.cancel()
            binderSuggestion = null
        }
        viewModelScope.launch {
            try {
                // externalScope, nie viewModelScope: sekwencja musi dobiec do końca nawet po
                // wyjściu z ekranu. Przy wygasłej sesji webowej ReauthInterceptor przechodzi
                // całą ścieżkę odzyskiwania (401 → CSRF → 401 → ciche logowanie → ponowienie)
                // i trwa to około dwóch sekund. Cofnięcie się w tym oknie — a robi się to
                // odruchowo, bo status w UI zmienia się od razu — niszczyło ViewModel, Retrofit
                // anulował Call i ponowienie po udanym logowaniu NIE szło już wcale. Zmierzone
                // na urządzeniu: `reauth: … -> SUCCESS`, a po nim ani POST-a, ani wpisu
                // `addToCollection code=`; serwer nie zapisywał, kapsel wracał na „Brak".
                // `await()` zostawia obsługę błędu tam, gdzie była — dopóki ekran żyje.
                externalScope.async {
                    if (status == CapStatus.MISSING) {
                        repository.removeFromCollection(current.cap.id)
                    } else if (!current.cap.isInCollection) {
                        repository.addToCollection(current.cap.id)
                    }
                    // Magazyn zakupionych dopiero po potwierdzeniu przez API — razem z zapisem
                    // idzie wpis do Firestore, a nikt go nie cofał, gdy wywołanie sieciowe padło.
                    // Zakładka Zakupione rozjeżdżała się wtedy z kolekcją po stronie serwera.
                    // Tylko dla kapsla już będącego w kolekcji: pozostałe ścieżki załatwiają
                    // magazyn same — addToCollection dopisuje, removeFromCollection usuwa.
                    if (status == CapStatus.PURCHASED && current.cap.isInCollection) {
                        repository.markPurchasedLocally(current.cap.id)
                    }
                    // Odpięcie na końcu. Przed wywołaniem API kasowało pozycję w klaserze także
                    // wtedy, gdy zmiana statusu nie przechodziła, a UI wracało do stanu
                    // „w klaserze" — do pozycji, której już nie było.
                    if (leavingCollection) {
                        capPositionRepository.unassign(current.cap.id.toLong())
                    }
                }.await()
            } catch (e: CancellationException) {
                // Ekran zniknął, gdy zapis był jeszcze w drodze. Sama operacja leci dalej
                // w externalScope — nie ma tu czego cofać ani czym straszyć użytkownika.
                throw e
            } catch (e: Exception) {
                capDetailUiState = current
                assignmentError = (e as? IllegalStateException)?.message ?: "Nie udało się zmienić statusu"
                if (leavingCollection && current.binderInfo != null) {
                    initBinderPreFill(current.binderInfo)
                }
            }
        }
    }

    fun getCap(id: Int) {
        viewModelScope.launch {
            capDetailUiState = try {
                var cap = repository.getById(id.toLong())
                val binderInfo = capPositionRepository.getBinderInfoByCapId(id.toLong())
                val status = when {
                    binderInfo != null -> CapStatus.IN_COLLECTION
                    cap.isInCollection -> CapStatus.PURCHASED
                    else -> CapStatus.MISSING
                }
                when (status) {
                    CapStatus.PURCHASED -> purchasedCapsLocalStore.add(id.toLong())
                    CapStatus.MISSING -> purchasedCapsLocalStore.remove(id.toLong())
                    else -> Unit
                }
                if (binderInfo != null) initBinderPreFill(binderInfo)
                val stored = capCacheRepository.getOne(id.toLong())
                // Ręcznie wybrany producent nadal istnieje na liście -> pokaż jego kraj zamiast
                // surowego cap.country ("-Multiple countries"). Jeśli zniknął, verify() go oflaguje.
                stored?.selectedProducerId?.let { producerId ->
                    cap.producers.firstOrNull { it.id == producerId }?.let { producer ->
                        cap = cap.copy(country = producer.country)
                    }
                }
                if (status == CapStatus.PURCHASED) launchSuggestion(cap.country.name, id.toLong())
                // Rozjazd dotyczy wyłącznie kapsli wstawionych w klaser — poza klaserem baner
                // nie ma czego naprawić, a w Roomie zostają stare flagi po wcześniejszych
                // odpięciach. Bez tego warunku baner wracał na kapslu bez klasera, z akcjami,
                // które nic już nie robiły.
                catalogStatus = if (binderInfo != null) stored?.catalogStatus else null
                catalogChanges =
                    if (binderInfo != null && stored != null) computeChanges(stored, cap) else emptyList()
                CapDetailUiState.Success(cap = cap, status = status, binderInfo = binderInfo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nie tylko IOException: Retrofit rzuca HttpException, gdy kapsla nie ma już
                // w katalogu (404). To dokładnie ten kapsel, który weryfikacja oznaczyła jako
                // „brak w katalogu" — czyli ten, w którego szczegóły wchodzi się po decyzję.
                // Wcześniej wyjątek wychodził poza viewModelScope i wywalał aplikację.
                CapDetailUiState.Error
            }
        }
    }

    private fun launchSuggestion(country: String, capId: Long) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            runCatching { binderSuggestion = computeSuggestion(country, capId) }
        }
    }

    private suspend fun computeSuggestion(country: String, capId: Long): BinderSuggestion? {
        val allBinders = binderRepository.getAll().first()
        val base = findBaseSuggestion(country, allBinders) ?: return null

        // Offset: count purchased caps from the same country displayed above this one in
        // Zakupione. That tab sorts by id descending, so "above" means a HIGHER id.
        val assignedIds = capPositionRepository.getAllCapIds().toSet()
        val priorIds = (purchasedCapsLocalStore.getIds() - assignedIds).filter { it > capId }
        if (priorIds.isNotEmpty()) {
            val countryForId = mutableMapOf<Long, String?>()
            priorIds.forEach { id -> countryForId[id] = capCacheRepository.getCountry(id) }
            val uncached = priorIds.filter { countryForId[it] == null }
            if (uncached.isNotEmpty()) {
                coroutineScope {
                    uncached.map { id ->
                        async { id to runCatching { repository.getById(id).country.name }.getOrNull() }
                    }.awaitAll()
                }.forEach { (id, c) ->
                    countryForId[id] = c
                    if (c != null) capCacheRepository.upsert(id, c)
                }
            }
            val offset = priorIds.count { countryForId[it] == country }
            if (offset > 0) return applyOffset(base, offset)
        }
        return base
    }

    private suspend fun findBaseSuggestion(country: String, allBinders: List<BinderView>): BinderSuggestion? {
        // Fast path: binders whose name starts with the country (e.g. "Polska 1", "Polska 2").
        // No API calls — caps in these binders are assumed to belong to that country.
        val nameMatched = allBinders.filter { it.name.startsWith(country, ignoreCase = true) }
        if (nameMatched.isNotEmpty()) {
            val target = nameMatched.maxBy { b ->
                b.name.removePrefix(country).trim().toIntOrNull() ?: 0
            }
            val pages = binderPageRepository.getByBinder(target.id).first()
            val pagePositions = coroutineScope {
                pages.map { page -> async { page to capPositionRepository.getByPage(page.id).first() } }.awaitAll()
            }.filter { (_, pos) -> pos.isNotEmpty() }
            // Klaser o najwyższym numerze bywa świeżo założony i jeszcze pusty — zakłada się go
            // właśnie wtedy, gdy poprzedni się zapełnił. Wcześniej sugestia w takiej chwili
            // znikała zamiast wskazać pierwszą wolną pozycję nowego klasera.
            val (bestPage, positions) = pagePositions.maxByOrNull { (page, _) -> page.pageNumber }
                ?: return BinderSuggestion(target.name, pages.minOfOrNull { it.pageNumber } ?: 1, 1)
            return buildSuggestion(target.name, bestPage.pageNumber, positions.maxOf { it.position })
        }

        // Slow path: binders not matched by name (e.g. "Europa 2" with mixed-country caps).
        // Load one representative cap per page in parallel, then fetch all uncached countries in parallel.
        val allPages = coroutineScope {
            allBinders.map { b -> async { binderPageRepository.getByBinder(b.id).first() } }.awaitAll()
        }.flatten()
        val pageLastPos = coroutineScope {
            allPages.map { page ->
                async { page to capPositionRepository.getByPage(page.id).first().maxByOrNull { it.id } }
            }.awaitAll()
        }.mapNotNull { (page, pos) -> if (pos != null) page to pos else null }

        // Batch-fetch all uncached countries: Room first, API for unknowns.
        val repIds = pageLastPos.map { (_, pos) -> pos.capId }.toSet()
        val countryForId = mutableMapOf<Long, String?>()
        repIds.forEach { id -> countryForId[id] = capCacheRepository.getCountry(id) }
        val uncachedRepIds = repIds.filter { countryForId[it] == null }
        if (uncachedRepIds.isNotEmpty()) {
            coroutineScope {
                uncachedRepIds.map { id ->
                    async { id to runCatching { repository.getById(id).country.name }.getOrNull() }
                }.awaitAll()
            }.forEach { (id, c) ->
                countryForId[id] = c
                if (c != null) capCacheRepository.upsert(id, c)
            }
        }

        val binderBestPage = mutableMapOf<Long, Pair<Int, Int>>() // binderId -> (pageNumber, maxPos)
        for ((page, repPos) in pageLastPos) {
            if (countryForId[repPos.capId] == country) {
                val maxPos = capPositionRepository.getByPage(page.id).first().maxOf { it.position }
                val existing = binderBestPage[page.binderId]
                if (existing == null || page.pageNumber > existing.first) {
                    binderBestPage[page.binderId] = page.pageNumber to maxPos
                }
            }
        }
        if (binderBestPage.isEmpty()) return null

        val bestBinderId = binderBestPage.keys.maxByOrNull { binderId ->
            allBinders.first { it.id == binderId }.name.substringAfterLast(" ").toIntOrNull() ?: 0
        } ?: return null
        val bestBinder = allBinders.first { it.id == bestBinderId }
        val (pageNum, maxPos) = binderBestPage[bestBinderId]!!
        return buildSuggestion(bestBinder.name, pageNum, maxPos)
    }

    private fun buildSuggestion(binderName: String, pageNumber: Int, maxPos: Int): BinderSuggestion {
        return when {
            maxPos < POSITIONS_PER_PAGE -> BinderSuggestion(binderName, pageNumber, maxPos + 1)
            pageNumber < PAGES_PER_BINDER -> BinderSuggestion(binderName, pageNumber + 1, 1)
            else -> {
                val prefix = binderName.substringBeforeLast(" ")
                val num = binderName.substringAfterLast(" ").toIntOrNull() ?: 1
                BinderSuggestion("$prefix ${num + 1}", 1, 1)
            }
        }
    }

    private fun applyOffset(suggestion: BinderSuggestion, offset: Int): BinderSuggestion {
        // Convert to 0-based absolute position across all pages of the binder.
        val absPos = (suggestion.pageNumber - 1) * POSITIONS_PER_PAGE + suggestion.nextPosition - 1 + offset
        val newPage = absPos / POSITIONS_PER_PAGE + 1
        val newPos = absPos % POSITIONS_PER_PAGE + 1
        if (newPage <= PAGES_PER_BINDER) return BinderSuggestion(suggestion.binderName, newPage, newPos)
        // Overflow into the next binder.
        val prefix = suggestion.binderName.substringBeforeLast(" ")
        val num = suggestion.binderName.substringAfterLast(" ").toIntOrNull() ?: 1
        val overflow = absPos - PAGES_PER_BINDER * POSITIONS_PER_PAGE
        return BinderSuggestion(
            "$prefix ${num + 1}",
            overflow / POSITIONS_PER_PAGE + 1,
            overflow % POSITIONS_PER_PAGE + 1
        )
    }

    private companion object {
        // Tylko założenie sugestii: tyle stron mieści klaser, którego używa właściciel kolekcji.
        // Dane tego nie pilnują — stronę o dowolnym numerze można dodać ręcznie.
        const val PAGES_PER_BINDER = 15
    }

    private fun initBinderPreFill(binderInfo: CapBinderInfo) {
        viewModelScope.launch {
            val allBinders = binderRepository.getAll().first()
            val binder = allBinders.firstOrNull { it.name == binderInfo.binderName } ?: return@launch
            selectedBinderId = binder.id
            pagesJob?.cancel()
            pagesJob = viewModelScope.launch {
                binderPageRepository.getByBinder(binder.id).collect { pages ->
                    binderPages = pages
                    if (selectedPageId == null) {
                        val page = pages.firstOrNull { it.pageNumber == binderInfo.pageNumber } ?: return@collect
                        selectedPageId = page.id
                        selectedPosition = binderInfo.position
                    }
                }
            }
        }
    }
}

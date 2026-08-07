package pl.sroki.cci.android.ui.binders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapsSearchRequest
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.CapSlot
import javax.inject.Inject

data class BindersUiState(
    val binders: List<BinderView> = emptyList(),
    val expandedBinderIds: Set<Long> = emptySet(),
    val expandedPageIds: Set<Long> = emptySet(),
    val binderPages: Map<Long, List<BinderPageView>> = emptyMap(),
    val capPositions: Map<Long, List<CapSlot>> = emptyMap(),
    val capInfo: Map<Long, Cap> = emptyMap(),
    // Wstawione kapsle z rozjazdem względem katalogu — oznaczane czerwoną pogrubioną czcionką.
    // Zbiór pochodzi wprost z Room i obejmuje całą kolekcję, nie tylko aktualnie wczytane strony.
    val flaggedCapIds: Set<Long> = emptySet(),
    val countries: List<Country> = emptyList(),
    val selectedCountryName: String = "",
    val isCreateDialogOpen: Boolean = false,
    val isLoading: Boolean = false,
    val deleteBinderConfirmId: Long? = null,
    val deletePageConfirmId: Long? = null,
    val renamePageTargetId: Long? = null,
    val movePageTargetId: Long? = null
)

sealed interface BindersEvent {
    data class ShowSnackbar(val message: String) : BindersEvent
}

@HiltViewModel
class BindersViewModel @Inject constructor(
    private val binderRepository: BinderRepository,
    private val binderPageRepository: BinderPageRepository,
    private val capPositionRepository: CapPositionRepository,
    private val capsRepository: CapsRepository,
    private val countriesRepository: CountriesRepository,
    private val capCacheRepository: CapCacheRepository,
    private val collectionVerifier: CollectionVerifier
) : ViewModel() {

    private val capInfoCache = mutableMapOf<Long, Cap>()

    // Kapsle, dla których katalog nie ma zdjęcia (znacznik image_unavailable w cap_cache).
    // Trzymane osobno od capInfoCache, żeby nie wstawiać do stanu UI pustych wpisów.
    private val capsWithoutImage = mutableSetOf<Long>()

    // Kapsle z zapytaniem w locie. Ten sam kapsel może leżeć na dwóch stronach (cap_position nie
    // ma unikalności na cap_id), a każda strona ma własny, równoległy kolektor pozycji. Wpis
    // w capInfoCache pojawia się dopiero po powrocie z API, więc bez tego zbioru oba kolektory
    // pytały o ten sam kapsel osobno.
    private val inFlightCapIds = mutableSetOf<Long>()

    // Ogranicza liczbę równoległych zapytań do API przy pierwszym (niezbuforowanym)
    // załadowaniu — bez tego strony×kapsle generowały tysiące jednoczesnych POST-ów.
    private val fetchSemaphore = Semaphore(8)

    private val _uiState = MutableStateFlow(BindersUiState())
    val uiState: StateFlow<BindersUiState> = _uiState.asStateFlow()

    private val _events = Channel<BindersEvent>(Channel.BUFFERED)
    val events: Flow<BindersEvent> = _events.receiveAsFlow()

    private val pageJobs = mutableMapOf<Long, Job>()
    private val capJobs = mutableMapOf<Long, Job>()
    private val binderToPageIds = mutableMapOf<Long, Set<Long>>()

    // Licznik, nie flaga: dwie równoległe operacje (np. dodanie strony i utworzenie klasera)
    // kończą się w dowolnej kolejności, a wspólny boolean gasł na pierwszej z nich.
    private var runningOperations = 0

    init {
        viewModelScope.launch {
            val countries = try { countriesRepository.getCountries() } catch (e: Exception) { emptyList() }
            _uiState.update { it.copy(countries = countries) }
        }
        // Pasywna weryfikacja inkrementalna — ~50 najdawniej sprawdzanych pozycji na wejście.
        // Wyniki dowozi flaggedCapsFlow poniżej, więc kolejność wobec ładowania stron nie ma znaczenia.
        viewModelScope.launch {
            runCatching { collectionVerifier.runIncremental(50) }
        }
        // Oflagowane kapsle prosto z Room. Jednorazowy odczyt po weryfikacji trafiał w moment,
        // w którym pozycje bywały jeszcze niewczytane, i rozjazdy nie pokazywały się do restartu.
        viewModelScope.launch {
            capCacheRepository.flaggedCapsFlow().collect { flagged ->
                val ids = flagged.map { it.capId }.toSet()
                _uiState.update { it.copy(flaggedCapIds = ids) }
            }
        }
        var previousIds = emptySet<Long>()
        viewModelScope.launch {
            binderRepository.getAll().collect { binders ->
                val newIds = binders.map { it.id }.toSet()
                val removedIds = previousIds - newIds
                val removedPageIds = removedIds.flatMap { binderToPageIds[it] ?: emptySet() }.toSet()
                removedIds.forEach { id ->
                    pageJobs.remove(id)?.cancel()
                    binderToPageIds.remove(id)
                }
                removedPageIds.forEach { capJobs.remove(it)?.cancel() }
                _uiState.update { state ->
                    state.copy(
                        binders = binders,
                        expandedBinderIds = state.expandedBinderIds - removedIds,
                        expandedPageIds = state.expandedPageIds - removedPageIds,
                        binderPages = state.binderPages - removedIds,
                        capPositions = state.capPositions - removedPageIds
                    )
                }
                previousIds = newIds
                binders.forEach { ensurePagesLoaded(it.id) }
            }
        }
    }

    /**
     * Strony ładowane są dla WSZYSTKICH klaserów, nie tylko rozwiniętych — ekran pokazuje
     * liczbę kapsli, listę krajów i oznaczenie rozjazdu na zwiniętym wierszu klasera, a filtr
     * kraju odsiewa całe klasery. Wszystko to wymaga znajomości pozycji z góry. Rozwinięcie
     * niczego więc nie doładowuje.
     */
    private fun ensurePagesLoaded(binderId: Long) {
        if (binderId in pageJobs) return
        pageJobs[binderId] = viewModelScope.launch {
            binderPageRepository.getByBinder(binderId).collect { pages ->
                val newPageIds = pages.map { it.id }.toSet()
                val oldPageIds = binderToPageIds[binderId] ?: emptySet()
                // Strona przeniesiona do innego klasera znika z tego Flow i pojawia się w Flow
                // klasera docelowego, a kolejność tych dwóch emisji jest niedeterministyczna.
                // Przy kolejności "docelowy, potem źródłowy" bez tego filtra anulowaliśmy właśnie
                // utworzony job i strona pokazywała się w nowym klaserze pusta.
                val removedPageIds = (oldPageIds - newPageIds).filterNot { pageId ->
                    binderToPageIds.any { (otherBinderId, ids) -> otherBinderId != binderId && pageId in ids }
                }.toSet()
                removedPageIds.forEach { pageId -> capJobs.remove(pageId)?.cancel() }
                newPageIds.filter { it !in capJobs }.forEach { pageId ->
                    capJobs[pageId] = viewModelScope.launch {
                        capPositionRepository.getByPage(pageId).collect { caps ->
                            _uiState.update { it.copy(capPositions = it.capPositions + (pageId to caps)) }
                            loadCapInfo(caps.map { it.capId })
                        }
                    }
                }
                binderToPageIds[binderId] = newPageIds
                _uiState.update { it.copy(
                    binderPages = it.binderPages + (binderId to pages),
                    capPositions = it.capPositions - removedPageIds
                ) }
            }
        }
    }

    /**
     * Uzupełnia [capInfoCache] danymi kapsli (kraj + zdjęcie). Najpierw czyta lokalny
     * cache Room (natychmiast), z API dociąga tylko te, których w cache brakuje lub nie
     * mają jeszcze zdjęcia, i zapisuje je z powrotem. Kapsel, dla którego katalog zdjęcia
     * nie ma, dostaje w cache znacznik `image_unavailable` — inaczej pusty `image_url` był
     * nieodróżnialny od "jeszcze nie pobraliśmy" i lądował w zapytaniu przy każdym wejściu.
     */
    private suspend fun loadCapInfo(capIds: List<Long>) {
        val needed = capIds.filter {
            it !in capInfoCache && it !in capsWithoutImage && it !in inFlightCapIds
        }
        if (needed.isEmpty()) return
        inFlightCapIds += needed
        try {
            val all = capCacheRepository.getByIds(needed)
            // Wpis jest kompletny, gdy ma zdjęcie albo wiemy, że zdjęcia nie będzie.
            val complete = all.filter { it.imageUrl.isNotEmpty() || it.imageUnavailable }
            capsWithoutImage += all.filter { it.imageUnavailable }.map { it.capId }
            complete.filter { it.country.isNotEmpty() || it.imageUrl.isNotEmpty() }
                .forEach { capInfoCache[it.capId] = it.toCap() }
            if (complete.isNotEmpty()) publishCapInfo()

            val toFetch = needed - complete.map { it.capId }.toSet()
            if (toFetch.isEmpty()) return

            coroutineScope {
                toFetch.map { capId ->
                    async {
                        fetchSemaphore.withPermit { fetchAndStore(capId) }
                    }
                }.awaitAll()
            }
            publishCapInfo()
        } finally {
            inFlightCapIds -= needed.toSet()
        }
    }

    private suspend fun fetchAndStore(capId: Long) {
        val fetched = runCatching {
            capsRepository.searchByFilter(
                CapsSearchRequest(id = capId.toInt()),
                page = 1
            ).data.firstOrNull()
        }
        // Błąd sieci zostawiamy bez znacznika — to stan przejściowy, następne wejście spróbuje
        // ponownie. Znacznik zapisujemy tylko dla odpowiedzi, która zdjęcia nie zawiera.
        val cap = fetched.getOrElse { return } ?: run {
            capCacheRepository.markImageUnavailable(capId)
            capsWithoutImage += capId
            return
        }
        capInfoCache[capId] = cap
        capCacheRepository.upsertFull(capId, cap.country, cap.imageUrl)
        if (cap.imageUrl.isEmpty()) {
            capCacheRepository.markImageUnavailable(capId)
            capsWithoutImage += capId
        }
    }

    private fun publishCapInfo() {
        _uiState.update { it.copy(capInfo = capInfoCache.toMap()) }
    }

    private fun CachedCap.toCap(): Cap = Cap(
        id = capId,
        description = name,
        country = country,
        product = "",
        liner = "",
        purpose = "",
        imageUrl = imageUrl
    )

    private suspend fun <T> withLoading(block: suspend () -> T): T {
        runningOperations++
        _uiState.update { it.copy(isLoading = true) }
        try {
            return block()
        } finally {
            runningOperations--
            if (runningOperations == 0) _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun setCountry(country: Country?) {
        _uiState.update { it.copy(selectedCountryName = country?.name ?: "") }
    }

    fun toggleExpand(binderId: Long) {
        _uiState.update { state ->
            val expanded = state.expandedBinderIds
            state.copy(
                expandedBinderIds = if (binderId in expanded) expanded - binderId else expanded + binderId
            )
        }
    }

    fun togglePageExpand(pageId: Long) {
        _uiState.update { state ->
            val expanded = state.expandedPageIds
            state.copy(expandedPageIds = if (pageId in expanded) expanded - pageId else expanded + pageId)
        }
    }

    fun showCreateDialog() = _uiState.update { it.copy(isCreateDialogOpen = true) }
    fun dismissCreateDialog() = _uiState.update { it.copy(isCreateDialogOpen = false) }

    fun createBinder(name: String) {
        viewModelScope.launch {
            withLoading {
                try {
                    binderRepository.create(name)
                    _uiState.update { it.copy(isCreateDialogOpen = false) }
                } catch (e: IllegalArgumentException) {
                    _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.send(BindersEvent.ShowSnackbar("Nie udało się utworzyć klasera"))
                }
            }
        }
    }

    fun requestDeleteBinder(binderId: Long) =
        _uiState.update { it.copy(deleteBinderConfirmId = binderId) }

    fun confirmDeleteBinder() {
        val binderId = _uiState.value.deleteBinderConfirmId ?: return
        _uiState.update { it.copy(deleteBinderConfirmId = null) }
        viewModelScope.launch {
            try {
                // Sprzątanie stanu i jobów robi kolektor binderRepository.getAll() — usunięty
                // klaser znika z jego kolejnej emisji. Powtórzenie tego tutaj dawało dwa
                // źródła prawdy dla jednej operacji.
                binderRepository.delete(binderId)
            } catch (e: IllegalStateException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się usunąć klasera"))
            }
        }
    }

    fun dismissDeleteBinder() = _uiState.update { it.copy(deleteBinderConfirmId = null) }

    fun addPage(binderId: Long) {
        viewModelScope.launch {
            withLoading {
                try {
                    binderPageRepository.addPage(binderId)
                } catch (e: IllegalStateException) {
                    _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.send(BindersEvent.ShowSnackbar("Nie udało się dodać strony"))
                }
            }
        }
    }

    fun requestDeletePage(pageId: Long) =
        _uiState.update { it.copy(deletePageConfirmId = pageId) }

    fun confirmDeletePage() {
        val pageId = _uiState.value.deletePageConfirmId ?: return
        _uiState.update { it.copy(deletePageConfirmId = null) }
        viewModelScope.launch {
            try {
                binderPageRepository.deletePage(pageId)
            } catch (e: IllegalStateException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się usunąć strony"))
            }
        }
    }

    fun dismissDeletePage() = _uiState.update { it.copy(deletePageConfirmId = null) }

    fun requestRenamePage(pageId: Long) = _uiState.update { it.copy(renamePageTargetId = pageId) }

    fun dismissRenamePage() = _uiState.update { it.copy(renamePageTargetId = null) }

    fun confirmRenamePage(newPageNumber: Int) {
        val pageId = _uiState.value.renamePageTargetId ?: return
        _uiState.update { it.copy(renamePageTargetId = null) }
        viewModelScope.launch {
            try {
                binderPageRepository.updatePageNumber(pageId, newPageNumber)
            } catch (e: IllegalStateException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: IllegalArgumentException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się zmienić numeru strony"))
            }
        }
    }

    fun requestMovePage(pageId: Long) = _uiState.update { it.copy(movePageTargetId = pageId) }

    fun dismissMovePage() = _uiState.update { it.copy(movePageTargetId = null) }

    fun confirmMovePage(newBinderId: Long) {
        val pageId = _uiState.value.movePageTargetId ?: return
        _uiState.update { it.copy(movePageTargetId = null) }
        viewModelScope.launch {
            try {
                binderPageRepository.moveToBinder(pageId, newBinderId)
            } catch (e: IllegalStateException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się przenieść strony"))
            }
        }
    }
}

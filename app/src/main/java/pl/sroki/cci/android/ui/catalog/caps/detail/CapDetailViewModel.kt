package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.model.BinderSuggestion
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.Producer
import pl.sroki.cci.android.model.toSnapshot
import java.io.IOException
import javax.inject.Inject

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
    private val purchasedCapsLocalStore: PurchasedCapsLocalStore
) : ViewModel() {

    var capDetailUiState: CapDetailUiState by mutableStateOf(CapDetailUiState.Loading)
        private set

    val isLoggedIn: StateFlow<Boolean> = sessionRepository.isLoggedIn

    var binders: List<Binder> by mutableStateOf(emptyList())
        private set
    var binderPages: List<BinderPage> by mutableStateOf(emptyList())
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
    var catalogStatus: String? by mutableStateOf(null)
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
            capCacheRepository.markVerified(capId, "ok", System.currentTimeMillis())
            catalogStatus = "ok"
        }
    }

    /** Rozjazd: zaakceptuj świeże dane z katalogu — nadpisz snapshot (Room + Firestore). */
    fun acceptNew() {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            val s = current.cap.toSnapshot()
            capCacheRepository.upsertSnapshot(
                capId, s.name, s.country, s.imageUrl, s.createdAt, s.createdById, s.updatedAt
            )
            capCacheRepository.markVerified(capId, "ok", System.currentTimeMillis())
            runCatching { capPositionRepository.updateSnapshot(capId, s) }
            catalogStatus = "ok"
        }
    }

    /** Ręczny wybór producenta dla kapsla "-Multiple countries" — nadpisuje kraj/producenta wszędzie. */
    fun selectProducer(producer: Producer) {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            capCacheRepository.selectProducer(capId, producer.id, producer.name, producer.country.name)
            capCacheRepository.markVerified(capId, "ok", System.currentTimeMillis())
            catalogStatus = "ok"
            capDetailUiState = current.copy(cap = current.cap.copy(country = producer.country))
        }
    }

    /** Rozjazd: odepnij kapsel z klasera. */
    fun unlinkFlagged() {
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            runCatching { capPositionRepository.unassign(capId) }
            catalogStatus = null
            capDetailUiState = current.copy(status = CapStatus.PURCHASED, binderInfo = null)
        }
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
                val newBinderInfo = capPositionRepository.getBinderInfoByCapId(capId)
                capDetailUiState = current.copy(
                    status = CapStatus.IN_COLLECTION,
                    binderInfo = newBinderInfo,
                    cap = current.cap.copy(isInCollection = true)
                )
                binderSuggestion = null
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
            repository.markPurchasedLocally(current.cap.id)
        } else {
            suggestionJob?.cancel()
            binderSuggestion = null
            if (status == CapStatus.MISSING) {
                repository.unmarkPurchasedLocally(current.cap.id)
            }
        }
        viewModelScope.launch {
            try {
                if (leavingCollection) {
                    capPositionRepository.unassign(current.cap.id.toLong())
                }
                if (status == CapStatus.MISSING) {
                    repository.removeFromCollection(current.cap.id)
                } else if (!current.cap.isInCollection) {
                    repository.addToCollection(current.cap.id)
                }
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
                var cap = repository.getById(id)
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
                catalogStatus = stored?.catalogStatus
                CapDetailUiState.Success(cap = cap, status = status, binderInfo = binderInfo)
            } catch (e: IOException) {
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

        // Offset: count purchased caps from the same country with a lower ID.
        // Lower ID → displayed earlier in Zakupione → gets a lower position suggestion.
        val assignedIds = capPositionRepository.getAllCapIds().toSet()
        val priorIds = (purchasedCapsLocalStore.getIds() - assignedIds).filter { it > capId }
        if (priorIds.isNotEmpty()) {
            val countryForId = mutableMapOf<Long, String?>()
            priorIds.forEach { id -> countryForId[id] = capCacheRepository.getCountry(id) }
            val uncached = priorIds.filter { countryForId[it] == null }
            if (uncached.isNotEmpty()) {
                coroutineScope {
                    uncached.map { id ->
                        async { id to runCatching { repository.getById(id.toInt()).country.name }.getOrNull() }
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

    private suspend fun findBaseSuggestion(country: String, allBinders: List<Binder>): BinderSuggestion? {
        // Fast path: binders whose name starts with the country (e.g. "Polska 1", "Polska 2").
        // No API calls — caps in these binders are assumed to belong to that country.
        val nameMatched = allBinders.filter { it.name.startsWith(country, ignoreCase = true) }
        if (nameMatched.isNotEmpty()) {
            val target = nameMatched.maxByOrNull { b ->
                b.name.removePrefix(country).trim().toIntOrNull() ?: 0
            } ?: return null
            val pages = binderPageRepository.getByBinder(target.id).first()
            val pagePositions = coroutineScope {
                pages.map { page -> async { page to capPositionRepository.getByPage(page.id).first() } }.awaitAll()
            }.filter { (_, pos) -> pos.isNotEmpty() }
            val (bestPage, positions) = pagePositions.maxByOrNull { (page, _) -> page.pageNumber } ?: return null
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
                    async { id to runCatching { repository.getById(id.toInt()).country.name }.getOrNull() }
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
            maxPos < 35 -> BinderSuggestion(binderName, pageNumber, maxPos + 1)
            pageNumber < 15 -> BinderSuggestion(binderName, pageNumber + 1, 1)
            else -> {
                val prefix = binderName.substringBeforeLast(" ")
                val num = binderName.substringAfterLast(" ").toIntOrNull() ?: 1
                BinderSuggestion("$prefix ${num + 1}", 1, 1)
            }
        }
    }

    private fun applyOffset(suggestion: BinderSuggestion, offset: Int): BinderSuggestion {
        // Convert to 0-based absolute position across all pages of the binder.
        val absPos = (suggestion.pageNumber - 1) * 35 + suggestion.nextPosition - 1 + offset
        val newPage = absPos / 35 + 1
        val newPos = absPos % 35 + 1
        if (newPage <= 15) return BinderSuggestion(suggestion.binderName, newPage, newPos)
        // Overflow into the next binder.
        val prefix = suggestion.binderName.substringBeforeLast(" ")
        val num = suggestion.binderName.substringAfterLast(" ").toIntOrNull() ?: 1
        val overflow = absPos - 15 * 35
        return BinderSuggestion("$prefix ${num + 1}", overflow / 35 + 1, overflow % 35 + 1)
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

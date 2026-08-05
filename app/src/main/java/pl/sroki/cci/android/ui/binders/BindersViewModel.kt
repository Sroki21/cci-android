package pl.sroki.cci.android.ui.binders

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.CapSlot
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapsSearchRequest
import javax.inject.Inject

data class BindersUiState(
    val binders: List<BinderView> = emptyList(),
    val expandedBinderIds: Set<Long> = emptySet(),
    val expandedPageIds: Set<Long> = emptySet(),
    val binderPages: Map<Long, List<BinderPageView>> = emptyMap(),
    val capPositions: Map<Long, List<CapSlot>> = emptyMap(),
    val capInfo: Map<Long, Cap> = emptyMap(),
    // capId -> catalog_status (do oznaczania rozjazdów czerwoną pogrubioną czcionką).
    val capStatus: Map<Long, String> = emptyMap(),
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

    var countries by mutableStateOf<List<Country>>(emptyList())
        private set

    var selectedCountryName by mutableStateOf("")
        private set

    fun setCountry(country: Country?) {
        selectedCountryName = country?.name ?: ""
    }

    private val capInfoCache = mutableMapOf<Long, Cap>()

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

    init {
        viewModelScope.launch {
            countries = try { countriesRepository.getCountries() } catch (e: Exception) { emptyList() }
        }
        // Pasywna weryfikacja inkrementalna — ~50 najdawniej sprawdzanych pozycji na wejście.
        viewModelScope.launch {
            runCatching { collectionVerifier.runIncremental(50) }
            refreshStatuses()
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
                        binderPages = state.binderPages - removedIds,
                        capPositions = state.capPositions - removedPageIds
                    )
                }
                previousIds = newIds
                binders.forEach { ensurePagesLoaded(it.id) }
            }
        }
    }

    private fun ensurePagesLoaded(binderId: Long) {
        if (binderId in pageJobs) return
        pageJobs[binderId] = viewModelScope.launch {
            binderPageRepository.getByBinder(binderId).collect { pages ->
                val newPageIds = pages.map { it.id }.toSet()
                val oldPageIds = binderToPageIds[binderId] ?: emptySet()
                val removedPageIds = oldPageIds - newPageIds
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
     * mają jeszcze zdjęcia, i zapisuje je z powrotem. Dzięki temu kolejne wejścia na ekran
     * nie generują żadnych zapytań sieciowych.
     */
    private suspend fun loadCapInfo(capIds: List<Long>) {
        val needed = capIds.filter { it !in capInfoCache }
        if (needed.isEmpty()) return

        val all = capCacheRepository.getByIds(needed)
        val statuses = all.associate { it.capId to it.catalogStatus }
        if (statuses.isNotEmpty()) _uiState.update { it.copy(capStatus = it.capStatus + statuses) }
        val cached = all.filter { it.imageUrl.isNotEmpty() }
        cached.forEach { capInfoCache[it.capId] = it.toCap() }
        if (cached.isNotEmpty()) _uiState.update { it.copy(capInfo = capInfoCache.toMap()) }

        val toFetch = needed - cached.map { it.capId }.toSet()
        if (toFetch.isEmpty()) return

        coroutineScope {
            toFetch.map { capId ->
                async {
                    fetchSemaphore.withPermit {
                        runCatching {
                            capsRepository.searchByFilter(
                                CapsSearchRequest(id = capId.toInt()),
                                page = 1
                            ).data.firstOrNull()
                        }.getOrNull()?.let { cap ->
                            capInfoCache[capId] = cap
                            capCacheRepository.upsertFull(capId, cap.country, cap.imageUrl)
                        }
                    }
                }
            }.awaitAll()
        }
        _uiState.update { it.copy(capInfo = capInfoCache.toMap()) }
    }

    // Po weryfikacji odśwież statusy dla aktualnie wczytanych pozycji (oznaczenia rozjazdów).
    private suspend fun refreshStatuses() {
        val capIds = _uiState.value.capPositions.values.flatten().map { it.capId }.distinct()
        if (capIds.isEmpty()) return
        val statuses = capCacheRepository.getByIds(capIds).associate { it.capId to it.catalogStatus }
        _uiState.update { it.copy(capStatus = it.capStatus + statuses) }
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

    fun toggleExpand(binderId: Long) {
        val expanded = _uiState.value.expandedBinderIds
        if (binderId in expanded) {
            _uiState.update { it.copy(expandedBinderIds = expanded - binderId) }
        } else {
            _uiState.update { it.copy(expandedBinderIds = expanded + binderId) }
            ensurePagesLoaded(binderId)
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
            _uiState.update { it.copy(isLoading = true) }
            try {
                binderRepository.create(name)
                _uiState.update { it.copy(isCreateDialogOpen = false) }
            } catch (e: IllegalArgumentException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się utworzyć klasera"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
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
                binderRepository.delete(binderId)
                pageJobs.remove(binderId)?.cancel()
                val pageIds = binderToPageIds.remove(binderId) ?: emptySet()
                pageIds.forEach { capJobs.remove(it)?.cancel() }
                _uiState.update { it.copy(
                    binderPages = it.binderPages - binderId,
                    capPositions = it.capPositions - pageIds,
                    expandedPageIds = it.expandedPageIds - pageIds
                ) }
            } catch (e: IllegalStateException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się usunąć klasera"))
            }
        }
    }

    fun dismissDeleteBinder() = _uiState.update { it.copy(deleteBinderConfirmId = null) }

    fun addPage(binderId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                binderPageRepository.addPage(binderId)
            } catch (e: IllegalStateException) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się dodać strony"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
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
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar(e.message ?: "Nie udało się usunąć strony"))
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
            } catch (e: Exception) {
                _events.send(BindersEvent.ShowSnackbar("Nie udało się przenieść strony"))
            }
        }
    }
}

package pl.sroki.cci.android.ui.binders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapsSearchRequest
import javax.inject.Inject

data class BindersUiState(
    val binders: List<Binder> = emptyList(),
    val expandedBinderIds: Set<Long> = emptySet(),
    val expandedPageIds: Set<Long> = emptySet(),
    val binderPages: Map<Long, List<BinderPage>> = emptyMap(),
    val capPositions: Map<Long, List<CapPosition>> = emptyMap(),
    val capInfo: Map<Long, Cap> = emptyMap(),
    val isCreateDialogOpen: Boolean = false,
    val isLoading: Boolean = false,
    val deleteBinderConfirmId: Long? = null,
    val deletePageConfirmId: Long? = null
)

sealed interface BindersEvent {
    data class ShowSnackbar(val message: String) : BindersEvent
}

@HiltViewModel
class BindersViewModel @Inject constructor(
    private val binderRepository: BinderRepository,
    private val binderPageRepository: BinderPageRepository,
    private val capPositionRepository: CapPositionRepository,
    private val capsRepository: CapsRepository
) : ViewModel() {

    private val capInfoCache = mutableMapOf<Long, Cap>()

    private val _uiState = MutableStateFlow(BindersUiState())
    val uiState: StateFlow<BindersUiState> = _uiState.asStateFlow()

    private val _events = Channel<BindersEvent>(Channel.BUFFERED)
    val events: Flow<BindersEvent> = _events.receiveAsFlow()

    private val pageJobs = mutableMapOf<Long, Job>()
    private val capJobs = mutableMapOf<Long, Job>()
    private val binderToPageIds = mutableMapOf<Long, Set<Long>>()

    init {
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
            }
        }
    }

    fun toggleExpand(binderId: Long) {
        val expanded = _uiState.value.expandedBinderIds
        if (binderId in expanded) {
            _uiState.update { it.copy(expandedBinderIds = expanded - binderId) }
        } else {
            _uiState.update { it.copy(expandedBinderIds = expanded + binderId) }
            if (binderId !in pageJobs) {
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
                                    val uncachedIds = caps.map { it.capId }.filter { it !in capInfoCache }
                                    if (uncachedIds.isNotEmpty()) {
                                        coroutineScope {
                                            uncachedIds.map { capId ->
                                                async {
                                                    runCatching {
                                                        capsRepository.searchByFilter(
                                                            CapsSearchRequest(id = capId.toInt()),
                                                            page = 1
                                                        ).data.firstOrNull()
                                                    }.getOrNull()?.let { cap -> capInfoCache[capId] = cap }
                                                }
                                            }.awaitAll()
                                        }
                                        _uiState.update { it.copy(capInfo = capInfoCache.toMap()) }
                                    }
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
}

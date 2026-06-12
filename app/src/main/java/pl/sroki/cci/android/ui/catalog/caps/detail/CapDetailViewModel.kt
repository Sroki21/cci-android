package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.model.CapExtended
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
    private val sessionRepository: SessionRepository,
    private val binderRepository: BinderRepository,
    private val binderPageRepository: BinderPageRepository
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

    private var pagesJob: Job? = null

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

    private fun saveAssignment() {
        val pageId = selectedPageId ?: return
        val position = selectedPosition ?: return
        val current = capDetailUiState as? CapDetailUiState.Success ?: return
        val capId = current.cap.id.toLong()
        viewModelScope.launch {
            isSaving = true
            assignmentError = null
            try {
                if (current.status == CapStatus.IN_COLLECTION) {
                    capPositionRepository.reassign(capId, pageId, position)
                } else {
                    if (!current.cap.isInCollection) {
                        repository.addToCollection(current.cap.id)
                    }
                    capPositionRepository.assign(pageId, position, capId)
                }
                val newBinderInfo = capPositionRepository.getBinderInfoByCapId(capId)
                capDetailUiState = current.copy(
                    status = CapStatus.IN_COLLECTION,
                    binderInfo = newBinderInfo,
                    cap = current.cap.copy(isInCollection = true)
                )
            } catch (e: Exception) {
                assignmentError = "Nie udało się przypisać: ${e.message}"
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
                if (leavingCollection && current.binderInfo != null) {
                    initBinderPreFill(current.binderInfo)
                }
            }
        }
    }

    fun getCap(id: Int) {
        viewModelScope.launch {
            capDetailUiState = try {
                val cap = repository.getById(id)
                val binderInfo = capPositionRepository.getBinderInfoByCapId(id.toLong())
                val status = when {
                    binderInfo != null -> CapStatus.IN_COLLECTION
                    cap.isInCollection -> CapStatus.PURCHASED
                    else -> CapStatus.MISSING
                }
                if (binderInfo != null) initBinderPreFill(binderInfo)
                CapDetailUiState.Success(cap = cap, status = status, binderInfo = binderInfo)
            } catch (e: IOException) {
                CapDetailUiState.Error
            }
        }
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

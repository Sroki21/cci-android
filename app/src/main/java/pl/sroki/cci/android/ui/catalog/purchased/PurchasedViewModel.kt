package pl.sroki.cci.android.ui.catalog.purchased

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.PurchasedCapsLocalStore
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.toCap
import javax.inject.Inject

sealed interface PurchasedUiState {
    data object Loading : PurchasedUiState
    data class Success(val caps: List<Cap>) : PurchasedUiState
    data class Error(val message: String) : PurchasedUiState
}

@HiltViewModel
class PurchasedViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    private val capPositionRepository: CapPositionRepository,
    private val purchasedCapsLocalStore: PurchasedCapsLocalStore
) : ViewModel() {

    private val _uiState = MutableStateFlow<PurchasedUiState>(PurchasedUiState.Loading)
    val uiState: StateFlow<PurchasedUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            capsRepository.collectionChanged.collect { load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = PurchasedUiState.Loading
            try {
                val assignedIds = capPositionRepository.getAllCapIds().toSet()
                val purchasedIds = purchasedCapsLocalStore.getIds() - assignedIds
                val caps = purchasedIds.mapNotNull { id ->
                    runCatching { capsRepository.getById(id.toInt()).toCap() }.getOrNull()
                }
                _uiState.value = PurchasedUiState.Success(caps)
            } catch (e: Exception) {
                _uiState.value = PurchasedUiState.Error(e.message ?: "Błąd ładowania")
            }
        }
    }
}

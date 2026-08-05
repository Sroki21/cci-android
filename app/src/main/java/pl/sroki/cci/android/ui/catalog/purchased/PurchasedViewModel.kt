package pl.sroki.cci.android.ui.catalog.purchased

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
                val caps = fetchCapsInParallel(purchasedIds)
                _uiState.value = PurchasedUiState.Success(caps)
            } catch (e: Exception) {
                _uiState.value = PurchasedUiState.Error(e.message ?: "Błąd ładowania")
            }
        }
    }

    /**
     * Wcześniej każdy kapsel był pobierany osobnym, sekwencyjnym żądaniem — przy kilkuset
     * zakupionych dawało to tyleż round-tripów jeden po drugim. Semafor i timeout jak
     * w StatisticsViewModel: równolegle, ale bez zalewania backendu crowncaps.
     */
    private suspend fun fetchCapsInParallel(ids: Set<Long>): List<Cap> {
        if (ids.isEmpty()) return emptyList()
        val semaphore = Semaphore(PARALLELISM)
        return coroutineScope {
            ids.map { id ->
                async {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        semaphore.withPermit {
                            runCatching { capsRepository.getById(id.toInt()).toCap() }.getOrNull()
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private companion object {
        const val PARALLELISM = 15
        const val REQUEST_TIMEOUT_MS = 6_000L
    }
}

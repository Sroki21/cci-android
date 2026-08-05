package pl.sroki.cci.android.ui.statistics.verification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.model.binder.CachedCap
import javax.inject.Inject

data class ScanState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

@HiltViewModel
class CollectionVerificationViewModel @Inject constructor(
    private val capCacheRepository: CapCacheRepository,
    private val capPositionRepository: CapPositionRepository,
    private val collectionVerifier: CollectionVerifier,
) : ViewModel() {

    val flaggedCaps: StateFlow<List<CachedCap>> = capCacheRepository.flaggedCapsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    @Volatile
    private var cancelRequested = false

    fun runFullScan() {
        if (_scan.value.running) return
        cancelRequested = false
        viewModelScope.launch {
            _scan.value = ScanState(running = true)
            runCatching {
                collectionVerifier.runFullScan(
                    onProgress = { done, total -> _scan.value = ScanState(true, done, total) },
                    isCancelled = { cancelRequested },
                )
            }
            _scan.value = ScanState(running = false)
        }
    }

    fun cancelScan() { cancelRequested = true }

    /** Zachowaj mój snapshot — oznacz rozjazd jako rozstrzygnięty. */
    fun keep(capId: Long) {
        viewModelScope.launch {
            capCacheRepository.markVerified(capId, "ok", System.currentTimeMillis())
        }
    }

    /** Odepnij kapsel z klasera. */
    fun unlink(capId: Long) {
        viewModelScope.launch { runCatching { capPositionRepository.unassign(capId) } }
    }
}

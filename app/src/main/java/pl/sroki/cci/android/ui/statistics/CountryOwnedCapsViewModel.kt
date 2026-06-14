package pl.sroki.cci.android.ui.statistics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

data class CountryOwnedCapsUiState(
    val country: String = "",
    val caps: List<Cap> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class CountryOwnedCapsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val capCacheRepository: CapCacheRepository,
    private val capsRepository: CapsRepository
) : ViewModel() {

    private val country: String = savedStateHandle.get<String>("country") ?: ""

    private val _uiState = MutableStateFlow(CountryOwnedCapsUiState(country = country))
    val uiState: StateFlow<CountryOwnedCapsUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            // Posiadane kapsle danego kraju z lokalnego cache (capId + zdjęcie) — bez API.
            val caps = capCacheRepository.getOwnedCapsByCountry(country).map { row ->
                Cap(
                    id = row.capId,
                    country = country,
                    product = "",
                    liner = "",
                    purpose = "",
                    imageUrl = row.imageUrl,
                    isInCollection = true
                )
            }
            _uiState.value = _uiState.value.copy(caps = caps, isLoading = false)

            // Backfill zdjęć dla kapsli, które w cache mają kraj, ale jeszcze nie URL.
            val missing = caps.filter { it.imageUrl.isBlank() }.map { it.id }
            if (missing.isNotEmpty()) backfillImages(missing)
        }
    }

    private suspend fun backfillImages(ids: List<Long>) {
        val semaphore = Semaphore(8)
        val updated = mutableMapOf<Long, String>()
        coroutineScope {
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        runCatching { capsRepository.getById(id.toInt()) }.getOrNull()?.let { cap ->
                            if (cap.imageUrl.isNotBlank()) {
                                capCacheRepository.upsertFull(id, country, cap.imageUrl)
                                updated[id] = cap.imageUrl
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (updated.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                caps = _uiState.value.caps.map { c -> updated[c.id]?.let { c.copy(imageUrl = it) } ?: c }
            )
        }
    }
}

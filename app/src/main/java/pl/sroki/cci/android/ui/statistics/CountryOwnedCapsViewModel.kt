package pl.sroki.cci.android.ui.statistics

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import pl.sroki.cci.android.model.CapExtended
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class CountryOwnedCapsUiState(
    val country: String = "",
    val caps: List<Cap> = emptyList(),
    val isLoading: Boolean = true,
    /** Niepuste, gdy wczytywanie padło — bez tego ekran zostawał na spinnerze na zawsze. */
    val error: String? = null
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

    fun retry() {
        if (_uiState.value.isLoading) return
        _uiState.value = CountryOwnedCapsUiState(country = country, isLoading = true)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            // Bez try/catch wyjątek zabijał korutynę, a isLoading zostawało true na zawsze.
            val caps = try {
                // Posiadane kapsle danego kraju z lokalnego cache (capId + zdjęcie) — bez API.
                capCacheRepository.getOwnedCapsByCountry(country).map { row ->
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CCI_UI", "nie udało się wczytać kapsli kraju $country", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Nie udało się wczytać kapsli"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(caps = caps, isLoading = false, error = null)

            // Backfill zdjęć dla kapsli, które w cache mają kraj, ale jeszcze nie URL.
            val missing = caps.filter { it.imageUrl.isBlank() }.map { it.id }
            if (missing.isNotEmpty()) backfillImages(missing)
        }
    }

    private suspend fun backfillImages(ids: List<Long>) {
        val semaphore = Semaphore(8)
        // Osiem równoległych korutyn dopisuje tu wyniki. Dziś trzyma się to kupy wyłącznie
        // dlatego, że viewModelScope działa na Dispatchers.Main.immediate — dołożenie
        // withContext(Dispatchers.IO) gdziekolwiek na tej ścieżce dałoby wyścig na LinkedHashMap.
        val updated = ConcurrentHashMap<Long, String>()
        coroutineScope {
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        pobierzKapsel(id)?.let { cap ->
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

    /** Brak zdjęcia jednego kapsla nie może przerwać backfillu, ale anulowanie musi przejść dalej. */
    private suspend fun pobierzKapsel(id: Long): CapExtended? =
        try {
            capsRepository.getById(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}

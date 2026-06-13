package pl.sroki.cci.android.ui.statistics

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
import kotlinx.coroutines.withTimeoutOrNull
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import javax.inject.Inject

data class CountryStat(val name: String, val count: Int)

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data class Success(
        val totalCaps: Int,
        val totalCountries: Int,
        val topCountries: List<CountryStat>,
        val isRefreshing: Boolean = false
    ) : StatisticsUiState
    data class Error(val message: String) : StatisticsUiState
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    private val capPositionRepository: CapPositionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<StatisticsUiState>(StatisticsUiState.Loading)
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = StatisticsUiState.Loading
            try {
                // Pokaż co jest w Room od razu
                showCurrentStats(isRefreshing = true)

                // Wypełnij brakujące kraje w tle (partiami po 30)
                val missingIds = capPositionRepository.getCapIdsWithoutCountry()
                if (missingIds.isNotEmpty()) {
                    fillMissingCountries(missingIds)
                }

                showCurrentStats(isRefreshing = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = StatisticsUiState.Error(e.message ?: "Błąd ładowania statystyk")
            }
        }
    }

    private suspend fun showCurrentStats(isRefreshing: Boolean) {
        val total = capPositionRepository.getTotalCount()
        val countryRows = capPositionRepository.getCountryStats()
        val allCountries = countryRows.map { CountryStat(it.country, it.count) }
        _uiState.value = StatisticsUiState.Success(
            totalCaps = total,
            totalCountries = allCountries.size,
            topCountries = allCountries.take(10),
            isRefreshing = isRefreshing
        )
    }

    private suspend fun fillMissingCountries(missingIds: List<Long>) {
        val semaphore = Semaphore(15)
        missingIds.chunked(30).forEach { batch ->
            coroutineScope {
                batch.map { id ->
                    async {
                        withTimeoutOrNull(6_000L) {
                            semaphore.withPermit {
                                runCatching { capsRepository.getById(id.toInt()).country.name }
                                    .getOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { country -> capPositionRepository.updateCountry(id, country) }
                            }
                        }
                    }
                }.awaitAll()
            }
            // Odśwież UI po każdej partii — użytkownik widzi postęp
            showCurrentStats(isRefreshing = true)
        }
    }
}

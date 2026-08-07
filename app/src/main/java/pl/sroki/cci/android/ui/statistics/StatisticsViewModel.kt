package pl.sroki.cci.android.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CountriesRepository
import javax.inject.Inject

data class CountryStat(val name: String, val count: Int, val flagUrl: String? = null)

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data class Success(
        val totalCaps: Int,
        val totalCountries: Int,
        val topCountries: List<CountryStat>,
        val allCountries: List<CountryStat>,
        val isRefreshing: Boolean = false
    ) : StatisticsUiState
    data class Error(val message: String) : StatisticsUiState
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    private val capPositionRepository: CapPositionRepository,
    private val capCacheRepository: CapCacheRepository,
    private val countriesRepository: CountriesRepository
) : ViewModel() {

    // name -> flagUrl; pobierane raz, opcjonalne (flagi to dodatek)
    private var flagByCountry: Map<String, String> = emptyMap()

    private val _uiState = MutableStateFlow<StatisticsUiState>(StatisticsUiState.Loading)
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    // Poprzedni przebieg jest anulowany przy każdym load(): seria uzupełniania krajów to setki
    // żądań, a dwa przebiegi naraz tylko biją się o te same id.
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = StatisticsUiState.Loading
            try {
                if (flagByCountry.isEmpty()) {
                    // Flagi to dodatek — ich brak nie może wywalić całego ekranu w stan Error.
                    flagByCountry = runCatching { countriesRepository.getFlagMap() }
                        .getOrDefault(emptyMap())
                }
                showCurrentStats(isRefreshing = true)
                val missingIds = capCacheRepository.getMissingForPositioned()
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
        val countryRows = capCacheRepository.getCountryStats() // posortowane wg liczby malejąco
        val byCount = countryRows.map { CountryStat(it.country, it.count, flagByCountry[it.country]) }
        val alphabetical = byCount.sortedBy { it.name.lowercase() }
        _uiState.value = StatisticsUiState.Success(
            totalCaps = total,
            totalCountries = byCount.size,
            topCountries = byCount.take(5),
            allCountries = alphabetical,
            isRefreshing = isRefreshing
        )
    }

    private suspend fun fillMissingCountries(missingIds: List<Long>) {
        val semaphore = Semaphore(15)
        missingIds.chunked(30).forEach { batch ->
            coroutineScope {
                batch.map { id ->
                    async {
                        // Permit PRZED timeoutem: odwrotna kolejność liczyła 6 s już od wejścia
                        // do kolejki, więc przy 30 id na 15 permitów druga połowa potrafiła
                        // wygasnąć, zanim w ogóle wystartowała — kraje gubiły się bez śladu.
                        semaphore.withPermit {
                            withTimeoutOrNull(6_000L) {
                                val cap = runCatching { capsRepository.getById(id) }.getOrNull()
                                if (cap != null) {
                                    val country = cap.country.name
                                    if (country.isNotBlank()) {
                                        // cache'uj kraj i zdjęcie (zakładka Kraje korzysta z image_url)
                                        capCacheRepository.upsertFull(id, country, cap.imageUrl)
                                    } else {
                                        // Katalog zna kapsel, ale nie ma dla niego kraju. Bez znacznika
                                        // taki wpis pasował do getMissingForPositioned NA ZAWSZE i przy
                                        // każdym wejściu na Statystyki znów szedł do API — z timeoutem
                                        // 6 s na sztukę. Klasery rozwiązały to samo znacznikiem
                                        // image_unavailable; tutaj używamy go w tej samej roli.
                                        capCacheRepository.markImageUnavailable(id)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            showCurrentStats(isRefreshing = true)
        }
    }
}

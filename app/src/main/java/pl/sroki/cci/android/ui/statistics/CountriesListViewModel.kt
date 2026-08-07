package pl.sroki.cci.android.ui.statistics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CountriesRepository
import javax.inject.Inject

data class CountriesListUiState(
    val countries: List<CountryStat> = emptyList(),
    val isLoading: Boolean = true,
    /** Niepuste, gdy wczytywanie padło — bez tego ekran zostawał na spinnerze na zawsze. */
    val error: String? = null
)

@HiltViewModel
class CountriesListViewModel @Inject constructor(
    private val capCacheRepository: CapCacheRepository,
    private val countriesRepository: CountriesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CountriesListUiState())
    val uiState: StateFlow<CountriesListUiState> = _uiState.asStateFlow()

    init { load() }

    fun retry() {
        if (_uiState.value.isLoading) return
        _uiState.value = CountriesListUiState(isLoading = true)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            // Bez try/catch wyjątek zabijał korutynę, a isLoading zostawało true na zawsze —
            // ekran wisiał na spinnerze bez słowa o tym, co się stało.
            try {
                // Flagi to dodatek pobierany z sieci (z lokalnym cache) — ich brak nie może
                // przesłonić listy krajów, która liczy się w całości z lokalnego Roomu.
                val flags = runCatching { countriesRepository.getFlagMap() }.getOrDefault(emptyMap())
                val list = capCacheRepository.getCountryStats()
                    .map { CountryStat(it.country, it.count, flags[it.country]) }
                    .sortedBy { it.name.lowercase() }
                _uiState.value = CountriesListUiState(countries = list, isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CCI_UI", "nie udało się wczytać listy krajów", e)
                _uiState.value = CountriesListUiState(
                    isLoading = false,
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Nie udało się wczytać krajów"
                )
            }
        }
    }
}

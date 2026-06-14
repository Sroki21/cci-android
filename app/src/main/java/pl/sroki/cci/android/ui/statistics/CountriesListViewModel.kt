package pl.sroki.cci.android.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CountriesRepository
import javax.inject.Inject

data class CountriesListUiState(
    val countries: List<CountryStat> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class CountriesListViewModel @Inject constructor(
    private val capCacheRepository: CapCacheRepository,
    private val countriesRepository: CountriesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CountriesListUiState())
    val uiState: StateFlow<CountriesListUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val flags = countriesRepository.getFlagMap()
            val list = capCacheRepository.getCountryStats()
                .map { CountryStat(it.country, it.count, flags[it.country]) }
                .sortedBy { it.name.lowercase() }
            _uiState.value = CountriesListUiState(countries = list, isLoading = false)
        }
    }
}

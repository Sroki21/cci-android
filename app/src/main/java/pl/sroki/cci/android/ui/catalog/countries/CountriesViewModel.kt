package pl.sroki.cci.android.ui.catalog.countries

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.model.Country
import java.io.IOException
import javax.inject.Inject

sealed interface CountriesUiState {
    data class Success(val countries: List<Country>) : CountriesUiState
    object Error : CountriesUiState
    object Loading : CountriesUiState
}

@HiltViewModel
class CountriesViewModel @Inject constructor(private val repository: CountriesRepository) : ViewModel() {
    /** The mutable State that stores the status of the most recent request */
    var countriesUiState: CountriesUiState by mutableStateOf(CountriesUiState.Loading)
        private set

    fun getCountries() {
        viewModelScope.launch {
            countriesUiState = try {
                val listResult = repository.getCountries()
                CountriesUiState.Success(listResult)
            } catch (e: IOException) {
                CountriesUiState.Error
            }
        }
    }
}

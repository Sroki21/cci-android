package pl.sroki.cci.android.ui.statistics.map

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
import java.util.Locale
import javax.inject.Inject

/**
 * Kraj na mapie. [apiName] (ang.) służy do nawigacji/filtra kapsli, [displayName] (pl) do wyświetlania.
 */
data class MapCountry(
    val iso: String,
    val apiName: String,
    val displayName: String,
    val count: Int,
    val flagUrl: String? = null,
)

sealed interface LocationsMapUiState {
    data object Loading : LocationsMapUiState
    data class Success(
        val map: WorldMap,
        val countries: Map<String, MapCountry>, // iso (lowercase) -> dane kraju (count = 0 gdy brak kapsli)
        val totalCaps: Int,
        val ownedCountriesCount: Int,
    ) : LocationsMapUiState
    data class Error(val message: String) : LocationsMapUiState
}

@HiltViewModel
class LocationsMapViewModel @Inject constructor(
    private val capCacheRepository: CapCacheRepository,
    private val countriesRepository: CountriesRepository,
    private val worldMapParser: WorldMapParser,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LocationsMapUiState>(LocationsMapUiState.Loading)
    val uiState: StateFlow<LocationsMapUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = LocationsMapUiState.Loading
            try {
                val map = worldMapParser.load()
                // iso/flagi to dodatek pobierany z sieci (z lokalnym cache) — ich brak nie może
                // przesłonić liczników kolekcji, które liczą się w całości z lokalnego Roomu.
                val isoByName = runCatching { countriesRepository.getIsoMap() }.getOrDefault(emptyMap())
                val flagByName = runCatching { countriesRepository.getFlagMap() }.getOrDefault(emptyMap())
                val countByName = capCacheRepository.getCountryStats()   // apiName -> count
                    .associate { it.country to it.count }

                // Wszystkie znane kraje (z count = 0, gdy brak kapsli), kluczowane kodem ISO.
                val countries = isoByName.entries.associate { (apiName, iso) ->
                    iso to MapCountry(
                        iso = iso,
                        apiName = apiName,
                        displayName = polishName(iso, fallback = apiName),
                        count = countByName[apiName] ?: 0,
                        flagUrl = flagByName[apiName],
                    )
                }

                // Liczniki idą z KOLEKCJI, nie z mapy. Sumowanie po `countries` pomijało kraje
                // o pseudo-kodach flag (MI, UN, CS, SU), bo te nie mają regionu na mapie i nie
                // wchodzą do isoByName — a kapsle z nich są w kolekcji tak samo jak wszystkie inne.
                // Licznik na mapie rozjeżdżał się przez to z licznikiem w Statystykach.
                _uiState.value = LocationsMapUiState.Success(
                    map = map,
                    countries = countries,
                    totalCaps = countByName.values.sum(),
                    ownedCountriesCount = countByName.count { it.value > 0 },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = LocationsMapUiState.Error(e.message ?: "Błąd ładowania mapy")
            }
        }
    }

    /** Polska nazwa kraju z kodu ISO; gdy locale nie zna kodu, zwraca [fallback]. */
    private fun polishName(iso: String, fallback: String): String {
        val name = Locale("", iso.uppercase()).getDisplayCountry(Locale("pl"))
        return if (name.isBlank() || name.equals(iso, ignoreCase = true)) fallback else name
    }
}

package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.local.dao.CountryFlagDao
import pl.sroki.cci.android.data.datasource.local.entity.CountryFlag
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import javax.inject.Inject

class CountriesRepository @Inject constructor(
    private val countryApiService: CountryApiService,
    private val countryFlagDao: CountryFlagDao
) {
    suspend fun getCountries(): List<Country> {
        return countryApiService.getCountries()
    }

    /**
     * Mapa nazwa kraju -> URL flagi. Czyta najpierw lokalny cache (Room); gdy pusty,
     * pobiera z API i zapisuje, więc kolejne wejścia są w pełni offline/szybkie.
     */
    suspend fun getFlagMap(): Map<String, String> {
        val cached = countryFlagDao.getAll()
        if (cached.isNotEmpty()) return cached.associate { it.name to it.imageUrl }

        val fetched = runCatching { countryApiService.getCountries() }.getOrNull() ?: return emptyMap()
        countryFlagDao.upsertAll(fetched.map { CountryFlag(it.name, it.imageUrl) })
        return fetched.associate { it.name to it.imageUrl }
    }
}

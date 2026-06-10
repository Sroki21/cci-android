package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import javax.inject.Inject

class CountriesRepository @Inject constructor(private val countryApiService: CountryApiService) {
    suspend fun getCountries(): List<Country> {
        return countryApiService.getCountries()
    }
}
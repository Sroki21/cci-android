package pl.sroki.cci.android.data.datasource.remote

import retrofit2.http.GET
import pl.sroki.cci.android.data.model.Country

interface CountryApiService {
    @GET("data/catalog/caps/countries")
    suspend fun getCountries(): List<Country>
}

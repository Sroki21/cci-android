package pl.sroki.cci.android.data.datasource.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.Page

interface CapApiService {
    @GET("api/v1/countries/{countryId}/caps")
    suspend fun getByCountryId(
        @Path("countryId") countryId: Int,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int
    ): Page<Cap>

    @GET("api/v1/caps/latest")
    suspend fun getLatest(
        @Query("page") page: Int,
        @Query("perPage") perPage: Int
    ): Page<Cap>

    @GET("api/v1/caps/{id}")
    suspend fun getById(@Path("id") id: Int): CapExtended

    @GET("api/v1/caps")
    suspend fun getByQuery(
        @Query("query") query: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int
    ): Page<Cap>

    @GET("api/v1/categories/caps")
    suspend fun getByCategoryIds(
        @Query("category[]") categoryIds: List<Int>,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int
    ): Page<Cap>
}

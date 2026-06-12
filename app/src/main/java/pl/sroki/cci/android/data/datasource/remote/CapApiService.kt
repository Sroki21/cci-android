package pl.sroki.cci.android.data.datasource.remote

import okhttp3.ResponseBody
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.CapsSearchRequest
import pl.sroki.cci.android.model.Page
import pl.sroki.cci.android.model.SimilarCapsResponse

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

    @GET("api/v1/caps")
    suspend fun advancedSearch(
        @Query("query") query: String? = null,
        @Query("country_id") countryId: Int? = null,
        @Query("producer") producer: String? = null,
        @Query("in_collection") inCollection: Int? = null,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int
    ): Page<Cap>

    @Multipart
    @POST("data/catalog/caps/similar")
    suspend fun searchSimilar(
        @Part image: MultipartBody.Part
    ): SimilarCapsResponse

    @POST("data/catalog/caps/search")
    suspend fun searchCapsByFilter(
        @Body request: CapsSearchRequest,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int
    ): Page<Cap>

    @POST("data/catalog/caps/{id}/collection")
    suspend fun addToCollection(@Path("id") id: Int): Response<ResponseBody>

    @DELETE("data/catalog/caps/{id}/collection")
    suspend fun removeFromCollection(@Path("id") id: Int): Response<ResponseBody>
}

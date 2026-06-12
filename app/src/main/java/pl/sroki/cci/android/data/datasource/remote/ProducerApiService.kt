package pl.sroki.cci.android.data.datasource.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface ProducerApiService {
    @GET("data/catalog/producers/names")
    suspend fun searchNames(@Query("name") name: String): List<String>
}

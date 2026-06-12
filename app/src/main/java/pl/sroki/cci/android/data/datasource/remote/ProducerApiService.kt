package pl.sroki.cci.android.data.datasource.remote

import pl.sroki.cci.android.model.Producer
import retrofit2.http.GET

interface ProducerApiService {
    @GET("api/v1/producers")
    suspend fun getAll(): List<Producer>
}

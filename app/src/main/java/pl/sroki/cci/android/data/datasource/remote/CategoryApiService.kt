package pl.sroki.cci.android.data.datasource.remote

import retrofit2.http.GET
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.Category

interface CategoryApiService {
    @GET("data/catalog/categories")
    suspend fun getAll(): List<Category>
}

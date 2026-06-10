package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.remote.CategoryApiService
import pl.sroki.cci.android.model.Category
import javax.inject.Inject

class CategoriesRepository @Inject constructor(private val categoriesRepository: CategoryApiService) {
    suspend fun getCategories(): List<Category> {
        return categoriesRepository.getAll()
    }
}
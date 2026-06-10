package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.remote.CapApiService
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.Page
import javax.inject.Inject

class CapsRepository @Inject constructor(private val capApiService: CapApiService) {

    private val perPage = Cap.PER_PAGE

    fun countryCapsPagingSource(id: Int) = CountryCapsPagingSource(id, this)
    fun latestCapsPagingSource() = LatestCapsPagingSource(this)
    fun quickSearchCapsPagingSource(query: String) = QuickSearchCapsPagingSource(query, this)
    fun pictureSearchCapsPagingSource(categoryIds: List<Int>) =
        PictureSearchCapsPagingSource(categoryIds, this)

    suspend fun getByCountryId(id: Int, page: Int = 1): Page<Cap> {
        return capApiService.getByCountryId(countryId = id, page = page, perPage = perPage)
    }

    suspend fun getByCategoryIds(categoryIds: List<Int>, page: Int = 1): Page<Cap> {
        return capApiService.getByCategoryIds(
            categoryIds = categoryIds,
            page = page,
            perPage = perPage
        )
    }

    suspend fun getLatest(page: Int = 1): Page<Cap> {
        return capApiService.getLatest(page = page, perPage = perPage)
    }

    suspend fun getById(id: Int): CapExtended {
        return capApiService.getById(id = id)
    }

    suspend fun getByQuery(query: String, page: Int = 1): Page<Cap> {
        return capApiService.getByQuery(query = query, page = page, perPage = perPage)
    }
}
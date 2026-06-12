package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap

private const val STARTING_KEY = 1

class AdvancedSearchPagingSource(
    private val filter: AdvancedSearchFilter,
    private val capsRepository: CapsRepository,
    private val onTotalLoaded: (Int) -> Unit
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        return try {
            val page = params.key ?: STARTING_KEY

            val hasTextFilter = filter.idValue.isNotBlank()
                || filter.textValue.isNotBlank()
                || filter.producerValue.isNotBlank()

            val result = when {
                // Wyłącznie kraj bez żadnych filtrów tekstowych → dedykowany endpoint
                filter.countryId != null && !hasTextFilter && !filter.onlyInCollection ->
                    capsRepository.getByCountryId(filter.countryId, page)

                // Wszystkie pozostałe kombinacje → endpoint tekstowy
                else -> {
                    val queryParts = buildList {
                        if (filter.idValue.isNotBlank()) add(filter.idValue.trim())
                        if (filter.textValue.isNotBlank()) add(filter.textValue.trim())
                        if (filter.producerValue.isNotBlank()) add(filter.producerValue.trim())
                        // Kraj + tekst: dołącz nazwę kraju do zapytania tekstowego
                        if (filter.countryId != null && hasTextFilter) add(filter.countryName)
                    }
                    val query = queryParts.joinToString(" ").takeIf { it.isNotBlank() }
                    capsRepository.advancedSearch(
                        query = query,
                        countryId = null,
                        producer = null,
                        inCollection = if (filter.onlyInCollection) 1 else null,
                        page = page
                    )
                }
            }

            if (page == STARTING_KEY) onTotalLoaded(result.total)

            LoadResult.Page(
                data = result.data,
                prevKey = if (page == STARTING_KEY) null else page - 1,
                nextKey = if (result.currentPage == result.lastPage) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.SearchOperator

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

            // ID — zawsze dokładne wyszukiwanie przez dedykowany endpoint
            val idInt = filter.idValue.trim().toIntOrNull()
            if (idInt != null) {
                if (page != STARTING_KEY) return LoadResult.Page(emptyList(), null, null)
                val cap = try { capsRepository.getById(idInt).toCap() } catch (e: Exception) { null }
                val data = listOfNotNull(cap)
                onTotalLoaded(data.size)
                return LoadResult.Page(data = data, prevKey = null, nextKey = null)
            }

            val hasTextFilter = filter.textValue.isNotBlank() || filter.producerValue.isNotBlank()

            val result = when {
                // Wyłącznie kraj → dedykowany endpoint
                filter.countryId != null && !hasTextFilter && !filter.onlyInCollection ->
                    capsRepository.getByCountryId(filter.countryId, page)

                // Wszystkie pozostałe kombinacje → endpoint tekstowy
                else -> {
                    val queryParts = buildList {
                        if (filter.textValue.isNotBlank()) add(filter.textValue.trim())
                        if (filter.producerValue.isNotBlank()) add(filter.producerValue.trim())
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

            val filteredData = applyOperatorFilter(result.data)
            if (page == STARTING_KEY) onTotalLoaded(result.total)

            LoadResult.Page(
                data = filteredData,
                prevKey = if (page == STARTING_KEY) null else page - 1,
                nextKey = if (result.currentPage == result.lastPage) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun applyOperatorFilter(data: List<Cap>): List<Cap> {
        if (filter.textValue.isBlank()) return data
        return when (filter.textOperator) {
            SearchOperator.CONTAINS -> data
            SearchOperator.EQUALS -> data.filter {
                it.description?.equals(filter.textValue.trim(), ignoreCase = true) == true
            }
            SearchOperator.STARTS_WITH -> data.filter {
                it.description?.startsWith(filter.textValue.trim(), ignoreCase = true) == true
            }
        }
    }
}

private fun CapExtended.toCap() = Cap(
    id = id.toLong(),
    description = description,
    country = country.name,
    product = product.name,
    liner = liner.name,
    purpose = purpose.name,
    imageUrl = imageUrl,
    isInCollection = isInCollection
)

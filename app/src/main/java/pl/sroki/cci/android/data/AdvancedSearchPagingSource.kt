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
    private val collectionCapIds: List<Long>?,
    private val onPageLoaded: (filteredCount: Int, apiTotal: Int?) -> Unit
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        return try {
            val page = params.key ?: STARTING_KEY

            // ID — pobierz CapExtended, pozostałe filtry client-side
            val idInt = filter.idValue.trim().toIntOrNull()
            if (idInt != null) {
                if (page != STARTING_KEY) return LoadResult.Page(emptyList(), null, null)
                val capExtended = try { capsRepository.getById(idInt) } catch (e: Exception) { null }
                val filtered = if (capExtended != null && matchesExtendedFilters(capExtended)) {
                    listOf(capExtended.toCap())
                } else emptyList()
                onPageLoaded(filtered.size, null)
                return LoadResult.Page(data = filtered, prevKey = null, nextKey = null)
            }

            // Kolekcja — paginacja po ID-kach z lokalnej bazy Room
            if (filter.onlyInCollection && collectionCapIds != null) {
                val pageSize = Cap.PER_PAGE
                val startIndex = (page - 1) * pageSize
                if (startIndex >= collectionCapIds.size) {
                    onPageLoaded(0, null)
                    return LoadResult.Page(emptyList(), null, null)
                }
                val endIndex = minOf(startIndex + pageSize, collectionCapIds.size)
                val batch = collectionCapIds.subList(startIndex, endIndex)
                val caps = batch.mapNotNull { id ->
                    try { capsRepository.getById(id.toInt()) } catch (e: Exception) { null }
                }.filter { matchesExtendedFilters(it) }
                    .map { it.toCap() }
                onPageLoaded(caps.size, null)
                val nextKey = if (endIndex >= collectionCapIds.size) null else page + 1
                return LoadResult.Page(
                    data = caps,
                    prevKey = if (page == STARTING_KEY) null else page - 1,
                    nextKey = nextKey
                )
            }

            val hasTextFilter = filter.textValue.isNotBlank()
            // Czysto krajowy (bez tekstu, producenta i kolekcji) → dedykowany endpoint
            val isPureCountry = filter.countryId != null && !hasTextFilter
                && filter.producerName.isBlank() && !filter.onlyInCollection

            // API ignoruje ?producer= — scalamy producenta z polem query
            val queryParts = buildList {
                if (filter.textValue.isNotBlank()) add(filter.textValue.trim())
                if (filter.producerName.isNotBlank()) add(filter.producerName.trim())
            }
            val mergedQuery = queryParts.joinToString(" ").takeIf { it.isNotBlank() }

            val result = if (isPureCountry) {
                capsRepository.getByCountryId(filter.countryId!!, page)
            } else {
                capsRepository.advancedSearch(
                    query = mergedQuery,
                    countryId = filter.countryId,
                    producer = null,
                    inCollection = if (filter.onlyInCollection) 1 else null,
                    page = page
                )
            }

            val filteredData = applyClientFilters(result.data, isPureCountry)

            // Licznik: filtry client-side → akumuluj; czyste CONTAINS bez kraju → API total
            val isClientFiltered = !isPureCountry && (
                (filter.textValue.isNotBlank() && filter.textOperator != SearchOperator.CONTAINS) ||
                filter.countryId != null
            )
            if (page == STARTING_KEY) {
                onPageLoaded(filteredData.size, if (!isClientFiltered) result.total else null)
            } else if (isClientFiltered) {
                onPageLoaded(filteredData.size, null)
            }

            LoadResult.Page(
                data = filteredData,
                prevKey = if (page == STARTING_KEY) null else page - 1,
                nextKey = if (result.currentPage == result.lastPage) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun applyClientFilters(data: List<Cap>, isPureCountry: Boolean): List<Cap> {
        var result = data
        if (filter.textValue.isNotBlank()) {
            val text = filter.textValue.trim()
            result = when (filter.textOperator) {
                SearchOperator.CONTAINS -> result
                SearchOperator.EQUALS -> result.filter {
                    it.description?.equals(text, ignoreCase = true) == true
                }
                SearchOperator.STARTS_WITH -> result.filter {
                    it.description?.startsWith(text, ignoreCase = true) == true
                }
            }
        }
        if (!isPureCountry && filter.countryId != null && filter.countryName.isNotBlank()) {
            result = result.filter { it.country.equals(filter.countryName, ignoreCase = true) }
        }
        return result
    }

    private fun matchesExtendedFilters(cap: CapExtended): Boolean {
        if (filter.textValue.isNotBlank()) {
            val text = filter.textValue.trim()
            val desc = cap.description ?: ""
            val ok = when (filter.textOperator) {
                SearchOperator.CONTAINS -> desc.contains(text, ignoreCase = true)
                SearchOperator.EQUALS -> desc.equals(text, ignoreCase = true)
                SearchOperator.STARTS_WITH -> desc.startsWith(text, ignoreCase = true)
            }
            if (!ok) return false
        }
        if (filter.producerName.isNotBlank()) {
            if (cap.producers.none { it.name.equals(filter.producerName, ignoreCase = true) }) return false
        }
        if (filter.countryId != null && cap.country.id.toInt() != filter.countryId) return false
        if (filter.onlyInCollection && !cap.isInCollection) return false
        return true
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

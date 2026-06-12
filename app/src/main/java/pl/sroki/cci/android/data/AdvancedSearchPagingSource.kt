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

            // Kolekcja → własny endpoint; client-side filtr tekstu i kraju
            if (filter.onlyInCollection) {
                val result = capsRepository.getCollection(
                    countryId = filter.countryId,
                    producer = filter.producerName.takeIf { it.isNotBlank() },
                    page = page
                )
                val filteredData = applyTextAndCountryFilter(result.data)
                val hasExtraFilters = filter.textValue.isNotBlank() || filter.countryId != null
                if (page == STARTING_KEY) {
                    onPageLoaded(filteredData.size, if (!hasExtraFilters) result.total else null)
                } else if (hasExtraFilters) {
                    onPageLoaded(filteredData.size, null)
                }
                return LoadResult.Page(
                    data = filteredData,
                    prevKey = if (page == STARTING_KEY) null else page - 1,
                    nextKey = if (result.currentPage == result.lastPage) null else page + 1
                )
            }

            val hasTextFilter = filter.textValue.isNotBlank()
            // Czysto krajowy: brak tekstu i producenta → dedykowany endpoint
            val isPureCountry = filter.countryId != null && !hasTextFilter && filter.producerName.isBlank()

            val result = if (isPureCountry) {
                capsRepository.getByCountryId(filter.countryId!!, page)
            } else {
                capsRepository.advancedSearch(
                    query = filter.textValue.trim().takeIf { it.isNotBlank() },
                    countryId = filter.countryId,
                    producer = filter.producerName.takeIf { it.isNotBlank() },
                    inCollection = null,
                    page = page
                )
            }

            val filteredData = applyClientFilters(result.data, isPureCountry)

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

    // Filtr tekstu + kraju dla wyników z endpointu kolekcji
    private fun applyTextAndCountryFilter(data: List<Cap>): List<Cap> {
        var result = data
        if (filter.textValue.isNotBlank()) {
            val text = filter.textValue.trim()
            result = when (filter.textOperator) {
                SearchOperator.CONTAINS -> result.filter {
                    it.description?.contains(text, ignoreCase = true) == true
                }
                SearchOperator.EQUALS -> result.filter {
                    it.description?.equals(text, ignoreCase = true) == true
                }
                SearchOperator.STARTS_WITH -> result.filter {
                    it.description?.startsWith(text, ignoreCase = true) == true
                }
            }
        }
        if (filter.countryId != null && filter.countryName.isNotBlank()) {
            result = result.filter { it.country.equals(filter.countryName, ignoreCase = true) }
        }
        return result
    }

    // Client-side filtry dla ścieżki advancedSearch
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

    // Pełny filtr AND na CapExtended (gdy ID jest podane)
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

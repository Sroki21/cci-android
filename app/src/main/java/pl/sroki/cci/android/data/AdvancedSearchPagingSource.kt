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
    // apiTotal != null tylko gdy API total jest dokładny (brak client-side filtrowania)
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

            val hasTextFilter = filter.textValue.isNotBlank()
            // Czysto krajowy: brak tekstu, producenta i kolekcji → dedykowany endpoint
            val isPureCountry = filter.countryId != null && !hasTextFilter
                && filter.producerName.isBlank() && !filter.onlyInCollection

            val result = if (isPureCountry) {
                capsRepository.getByCountryId(filter.countryId!!, page)
            } else {
                capsRepository.advancedSearch(
                    query = filter.textValue.trim().takeIf { it.isNotBlank() },
                    countryId = filter.countryId,
                    producer = filter.producerName.takeIf { it.isNotBlank() },
                    inCollection = if (filter.onlyInCollection) 1 else null,
                    page = page
                )
            }

            val filteredData = applyClientFilters(result.data, isPureCountry)

            // Licznik: jeśli stosujemy client-side filtrowanie → akumuluj; inaczej → API total
            val isClientFiltered = !isPureCountry && (
                (filter.textValue.isNotBlank() && filter.textOperator != SearchOperator.CONTAINS) ||
                filter.countryId != null ||
                filter.onlyInCollection
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

    // Client-side filtry na wynikach z API (fallback gdy API ignoruje parametry)
    private fun applyClientFilters(data: List<Cap>, isPureCountry: Boolean): List<Cap> {
        var result = data
        // Operator tekstowy (EQUALS/STARTS_WITH na description)
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
        // Kraj (client-side fallback gdy API ignoruje country_id w advancedSearch)
        if (!isPureCountry && filter.countryId != null && filter.countryName.isNotBlank()) {
            result = result.filter { it.country.equals(filter.countryName, ignoreCase = true) }
        }
        // Kolekcja (client-side fallback gdy API ignoruje in_collection)
        if (filter.onlyInCollection) {
            result = result.filter { it.isInCollection }
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

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
    // apiTotal non-null only when API total is accurate (no client-side filtering)
    private val onPageLoaded: (filteredCount: Int, apiTotal: Int?) -> Unit
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        return try {
            val page = params.key ?: STARTING_KEY

            // ID — dokładne wyszukiwanie: pozostałe filtry stosowane client-side na CapExtended
            val idInt = filter.idValue.trim().toIntOrNull()
            if (idInt != null) {
                if (page != STARTING_KEY) return LoadResult.Page(emptyList(), null, null)
                val capExtended = try { capsRepository.getById(idInt) } catch (e: Exception) { null }
                val filtered = if (capExtended != null && matchesExtendedFilters(capExtended)) {
                    listOf(capExtended.toCap())
                } else {
                    emptyList()
                }
                onPageLoaded(filtered.size, null)
                return LoadResult.Page(data = filtered, prevKey = null, nextKey = null)
            }

            val hasTextFilter = filter.textValue.isNotBlank() || filter.producerValue.isNotBlank()

            val result = when {
                // Wyłącznie kraj (bez tekstu i kolekcji) → dedykowany endpoint
                filter.countryId != null && !hasTextFilter && !filter.onlyInCollection ->
                    capsRepository.getByCountryId(filter.countryId, page)

                // Wszystkie pozostałe kombinacje → advancedSearch z countryId
                else -> {
                    val queryParts = buildList {
                        if (filter.textValue.isNotBlank()) add(filter.textValue.trim())
                        if (filter.producerValue.isNotBlank()) add(filter.producerValue.trim())
                    }
                    val query = queryParts.joinToString(" ").takeIf { it.isNotBlank() }
                    capsRepository.advancedSearch(
                        query = query,
                        countryId = filter.countryId,
                        producer = null,
                        inCollection = if (filter.onlyInCollection) 1 else null,
                        page = page
                    )
                }
            }

            val filteredData = applyTextFilter(result.data)

            // Licznik: CONTAINS → użyj API total (dokładny); EQUALS/STARTS_WITH → akumuluj
            val isClientFiltered = filter.textValue.isNotBlank() &&
                filter.textOperator != SearchOperator.CONTAINS
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

    // Client-side filtr operatora tekstowego na polu description
    private fun applyTextFilter(data: List<Cap>): List<Cap> {
        if (filter.textValue.isBlank()) return data
        val text = filter.textValue.trim()
        return when (filter.textOperator) {
            SearchOperator.CONTAINS -> data
            SearchOperator.EQUALS -> data.filter {
                it.description?.equals(text, ignoreCase = true) == true
            }
            SearchOperator.STARTS_WITH -> data.filter {
                it.description?.startsWith(text, ignoreCase = true) == true
            }
        }
    }

    // Pełne filtry AND na CapExtended (używane gdy ID jest ustawione)
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
        if (filter.producerValue.isNotBlank()) {
            val prod = filter.producerValue.trim()
            val anyMatch = cap.producers.any { p ->
                when (filter.producerOperator) {
                    SearchOperator.CONTAINS -> p.name.contains(prod, ignoreCase = true)
                    SearchOperator.EQUALS -> p.name.equals(prod, ignoreCase = true)
                    SearchOperator.STARTS_WITH -> p.name.startsWith(prod, ignoreCase = true)
                }
            }
            if (!anyMatch) return false
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

package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.CapsSearchRequest
import pl.sroki.cci.android.model.SearchOperator

private const val STARTING_KEY = 1

class AdvancedSearchPagingSource(
    private val filter: AdvancedSearchFilter,
    private val capsRepository: CapsRepository,
    private val onPageLoaded: (filteredCount: Int, apiTotal: Int?) -> Unit
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(1) ?: anchorPage.nextKey?.minus(1)
    }

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
            val hasProducer = filter.producerName.isNotBlank()
            // Kraj (bez tekstu i producenta) → dedykowany endpoint; kolekcja obsługiwana client-side
            val isPureCountry = filter.countryId != null && !hasTextFilter && !hasProducer

            val result = when {
                isPureCountry -> {
                    capsRepository.getByCountryId(filter.countryId ?: 0, page)
                }
                hasProducer -> {
                    // POST /data/catalog/caps/search obsługuje pole producer bezpośrednio
                    val descMethod = when (filter.textOperator) {
                        SearchOperator.CONTAINS -> 4
                        SearchOperator.EQUALS -> 1
                        SearchOperator.STARTS_WITH -> 2
                    }
                    capsRepository.searchByFilter(
                        CapsSearchRequest(
                            producer = filter.producerName.trim(),
                            description = filter.textValue.trim().takeIf { it.isNotBlank() },
                            descriptionMethod = if (hasTextFilter) descMethod else 4,
                            countryId = filter.countryId,
                            inCollection = if (filter.onlyInCollection) true else null,
                            productId = 1
                        ),
                        page = page
                    )
                }
                else -> {
                    capsRepository.advancedSearch(
                        query = filter.textValue.trim().takeIf { it.isNotBlank() },
                        countryId = filter.countryId,
                        producer = null,
                        inCollection = if (filter.onlyInCollection) 1 else null,
                        page = page
                    )
                }
            }

            val filteredData = applyClientFilters(result.data, isPureCountry)

            // CONTAINS bez innych filtrów → licznik z API od razu (może być nieścisły gdy API
            // ignoruje productId=1, ale lepsze niż rosnący licznik dla normalnych wyszukiwań)
            val isClientFiltered = filter.onlyInCollection || (!isPureCountry && (
                (filter.textValue.isNotBlank() && filter.textOperator != SearchOperator.CONTAINS) ||
                filter.countryId != null
            ))
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
        var result = data.filter {
            it.product.equals("Piwo", ignoreCase = true) ||
            it.product.equals("Beer", ignoreCase = true)
        }
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
        // isPureCountry (getByCountryId) nie przyjmuje parametru inCollection, więc trzeba
        // dofiltrować lokalnie. Gałąź hasProducer (data/catalog/caps/search) prosi API o
        // inCollection=true, ale ten endpoint bywa zawodny w honorowaniu tego parametru —
        // CapsRepository.searchByFilter naprawia już pole isInCollection lokalnie
        // (enrichWithLocalCollectionStatus), więc filtrowanie po nim tutaj jest bezpieczne
        // i konieczne jako siatka bezpieczeństwa. advancedSearch (zwykłe query/kraj) tego
        // problemu nie ma — zostaje bez zmian.
        if (filter.onlyInCollection && (isPureCountry || filter.producerName.isNotBlank())) {
            result = result.filter { it.isInCollection }
        }
        return result
    }

    private fun matchesExtendedFilters(cap: CapExtended): Boolean {
        if (cap.product.id != 1) return false
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

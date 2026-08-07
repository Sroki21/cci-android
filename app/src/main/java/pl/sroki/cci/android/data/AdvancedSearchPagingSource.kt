package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.CapsSearchRequest
import pl.sroki.cci.android.model.Page
import pl.sroki.cci.android.model.SearchOperator
import retrofit2.HttpException

private const val STARTING_KEY = 1

class AdvancedSearchPagingSource(
    private val filter: AdvancedSearchFilter,
    private val capsRepository: CapsRepository,
    private val onPageLoaded: (filteredCount: Int, apiTotal: Int?) -> Unit
) : PagingSource<Int, Cap>() {

    private val hasTextFilter = filter.textValue.isNotBlank()
    private val hasProducer = filter.producerName.isNotBlank()

    /**
     * Kraj bez tekstu i producenta → dedykowany endpoint `getByCountryId`. Trzymamy tu samo id
     * zamiast flagi, żeby gałąź zapytania nie musiała odzyskiwać wartości fallbackiem `?: 0`,
     * który przy zmianie warunku wyżej poleciałby jako zapytanie o nieistniejący kraj.
     */
    private val pureCountryId: Int? =
        filter.countryId.takeIf { !hasTextFilter && !hasProducer }

    // Powtórzony kapsel na styku stron wywalał listę — patrz PageDeduplicator.
    private val dedup = PageDeduplicator()

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(1) ?: anchorPage.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        val page = params.key ?: STARTING_KEY

        // ID — pobierz CapExtended, pozostałe filtry client-side
        filter.idValue.trim().toLongOrNull()?.let { return loadById(it, page) }

        return try {
            var lastFetched = page
            var result = fetchPage(lastFetched)
            var filteredData = applyClientFilters(result.data)
            var extraFetches = 0

            // Filtrowanie client-side potrafi wyzerować całą stronę (np. „tylko w kolekcji"
            // na czystym kraju). Bez dobrania kolejnych użytkownik widzi pustkę, choć wyniki
            // dalej są. Limit, żeby przy bardzo wąskim filtrze jedno przewinięcie nie ciągnęło
            // pół katalogu.
            while (filteredData.isEmpty() && result.currentPage != result.lastPage &&
                extraFetches < MAX_EXTRA_PAGE_FETCHES
            ) {
                lastFetched++
                extraFetches++
                result = fetchPage(lastFetched)
                filteredData = applyClientFilters(result.data)
            }

            // CONTAINS bez innych filtrów → licznik z API od razu (może być nieścisły gdy API
            // ignoruje productId=1, ale lepsze niż rosnący licznik dla normalnych wyszukiwań)
            val isClientFiltered = filter.onlyInCollection || (pureCountryId == null && (
                (hasTextFilter && filter.textOperator != SearchOperator.CONTAINS) ||
                    filter.countryId != null
                ))
            if (page == STARTING_KEY) {
                onPageLoaded(filteredData.size, if (!isClientFiltered) result.total else null)
            } else if (isClientFiltered) {
                onPageLoaded(filteredData.size, null)
            }

            LoadResult.Page(
                data = dedup.odsiej(filteredData),
                prevKey = if (page == STARTING_KEY) null else page - 1,
                nextKey = if (result.currentPage == result.lastPage) null else lastFetched + 1
            )
        } catch (e: CancellationException) {
            // Anulowanie (użytkownik zmienił filtr) nie jest błędem ładowania. Bez tego
            // `catch (e: Exception)` łapało je razem z resztą — CancellationException jest na
            // JVM podklasą IllegalStateException — i nieaktualne ładowanie meldowało się w UI
            // jako błąd.
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private suspend fun fetchPage(page: Int): Page<Cap> {
        val countryId = pureCountryId
        return when {
            countryId != null -> capsRepository.getByCountryId(countryId, page)
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
                        productId = BEER_PRODUCT_ID
                    ),
                    page = page
                )
            }
            else -> capsRepository.advancedSearch(
                query = filter.textValue.trim().takeIf { it.isNotBlank() },
                countryId = filter.countryId,
                producer = null,
                inCollection = if (filter.onlyInCollection) 1 else null,
                page = page
            )
        }
    }

    private suspend fun loadById(id: Long, page: Int): LoadResult<Int, Cap> {
        if (page != STARTING_KEY) return LoadResult.Page(emptyList(), null, null)

        val cap = try {
            capsRepository.getById(id)
        } catch (e: HttpException) {
            // 404 to poprawna odpowiedź: takiego kapsla nie ma, lista jest pusta. Każdy inny
            // błąd (sieć, bramka Cloudflare) musi dojść do UI jako błąd — „brak wyników" to
            // zupełnie inna informacja niż „nie udało się sprawdzić".
            if (e.code() == 404) null else return LoadResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }

        val filtered = if (cap != null && matchesExtendedFilters(cap)) {
            listOf(cap.toCap())
        } else {
            emptyList()
        }
        onPageLoaded(filtered.size, null)
        return LoadResult.Page(data = filtered, prevKey = null, nextKey = null)
    }

    private fun applyClientFilters(data: List<Cap>): List<Cap> {
        var result = data.filter { it.product.trim().lowercase() in BEER_PRODUCT_NAMES }
        if (hasTextFilter) {
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
        if (pureCountryId == null && filter.countryId != null && filter.countryName.isNotBlank()) {
            result = result.filter { it.country.equals(filter.countryName, ignoreCase = true) }
        }
        // getByCountryId nie przyjmuje parametru inCollection, więc trzeba dofiltrować lokalnie.
        // Gałąź hasProducer (data/catalog/caps/search) prosi API o inCollection=true, ale ten
        // endpoint bywa zawodny w honorowaniu tego parametru — CapsRepository.searchByFilter
        // naprawia już pole isInCollection lokalnie (enrichWithLocalCollectionStatus), więc
        // filtrowanie po nim tutaj jest bezpieczne i konieczne jako siatka bezpieczeństwa.
        // advancedSearch (zwykłe query/kraj) tego problemu nie ma — zostaje bez zmian.
        if (filter.onlyInCollection && (pureCountryId != null || hasProducer)) {
            result = result.filter { it.isInCollection }
        }
        return result
    }

    private fun matchesExtendedFilters(cap: CapExtended): Boolean {
        if (cap.product.id != BEER_PRODUCT_ID) return false
        if (hasTextFilter) {
            val text = filter.textValue.trim()
            val desc = cap.description ?: ""
            val ok = when (filter.textOperator) {
                SearchOperator.CONTAINS -> desc.contains(text, ignoreCase = true)
                SearchOperator.EQUALS -> desc.equals(text, ignoreCase = true)
                SearchOperator.STARTS_WITH -> desc.startsWith(text, ignoreCase = true)
            }
            if (!ok) return false
        }
        if (hasProducer) {
            if (cap.producers.none { it.name.equals(filter.producerName, ignoreCase = true) }) {
                return false
            }
        }
        if (filter.countryId != null && cap.country.id.toInt() != filter.countryId) return false
        if (filter.onlyInCollection && !cap.isInCollection) return false
        return true
    }

    private companion object {
        const val BEER_PRODUCT_ID = 1

        /**
         * Nazwy produktu uznawane za piwo przy filtrowaniu list.
         *
         * Świadomy dług: listowe `/api/v1/caps` zwraca produkt jako sam napis (`"product":"Piwo"`),
         * bez identyfikatora, więc na liście nie da się porównać po [BEER_PRODUCT_ID] tak jak
         * w [matchesExtendedFilters], gdzie pracujemy na `CapExtended` ze szczegółów. Napis
         * przychodzi zlokalizowany (aplikacja wymusza `user-locale=pl`), stąd oba warianty.
         * Właściwa naprawa wymaga wystawienia id produktu w odpowiedzi listowej po stronie API.
         */
        val BEER_PRODUCT_NAMES = setOf("piwo", "beer")

        /** Ile dodatkowych stron wolno dobrać, gdy filtr client-side wyzeruje bieżącą. */
        const val MAX_EXTRA_PAGE_FETCHES = 5
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

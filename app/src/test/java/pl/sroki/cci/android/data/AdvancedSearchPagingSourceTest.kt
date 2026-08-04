package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.Page

class AdvancedSearchPagingSourceTest {

    private fun cap(id: Long, isInCollection: Boolean) = Cap(
        id = id, country = "Polska", product = "Piwo", liner = "Plastic",
        purpose = "Bottle closure", imageUrl = "https://example.com/$id.jpg",
        isInCollection = isInCollection
    )

    private fun loadParams() = PagingSource.LoadParams.Refresh<Int>(
        key = null, loadSize = Cap.PER_PAGE, placeholdersEnabled = false
    )

    @Test
    fun `producent plus tylko w kolekcji filtruje lokalnie gdy API nie honoruje inCollection`() = runTest {
        val capsRepository = mockk<CapsRepository>()
        val filter = AdvancedSearchFilter(producerName = "Heineken", onlyInCollection = true)
        // Endpoint producenta (data/catalog/caps/search) bywa zawodny w honorowaniu inCollection=true
        // w zapytaniu — zwraca kapsle spoza kolekcji mimo filtra. CapsRepository.searchByFilter
        // naprawia isInCollection lokalnie przed zwróceniem, więc client-side filtr po tym
        // (już zenrichowanym) polu musi je odciąć.
        coEvery { capsRepository.searchByFilter(any(), 1) } returns Page(
            data = listOf(cap(1L, isInCollection = false), cap(2L, isInCollection = true)),
            lastPage = 1, currentPage = 1, perPage = Cap.PER_PAGE, total = 2
        )
        val source = AdvancedSearchPagingSource(filter, capsRepository) { _, _ -> }

        val result = source.load(loadParams()) as PagingSource.LoadResult.Page

        assertEquals(1, result.data.size)
        assertEquals(2L, result.data[0].id)
    }

    @Test
    fun `wyszukiwanie po kraju bez producenta nadal filtruje lokalnie po isInCollection`() = runTest {
        val capsRepository = mockk<CapsRepository>()
        val filter = AdvancedSearchFilter(countryId = 1, countryName = "Polska", onlyInCollection = true)
        // getByCountryId nie wspiera parametru inCollection — tu client-side filtr musi zostać.
        coEvery { capsRepository.getByCountryId(1, 1) } returns Page(
            data = listOf(cap(1L, isInCollection = false), cap(2L, isInCollection = true)),
            lastPage = 1, currentPage = 1, perPage = Cap.PER_PAGE, total = 2
        )
        val source = AdvancedSearchPagingSource(filter, capsRepository) { _, _ -> }

        val result = source.load(loadParams()) as PagingSource.LoadResult.Page

        assertEquals(1, result.data.size)
        assertEquals(2L, result.data[0].id)
    }
}

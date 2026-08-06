package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.Page
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

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

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
    )

    @Test
    fun `szukanie po ID — brak lacznosci daje blad, nie pusta liste`() = runTest {
        // "Nie znaleziono" i "nie udało się sprawdzić" to dwie różne informacje dla użytkownika.
        val capsRepository = mockk<CapsRepository>()
        coEvery { capsRepository.getById(150627) } throws IOException("brak sieci")
        val source = AdvancedSearchPagingSource(
            AdvancedSearchFilter(idValue = "150627"), capsRepository
        ) { _, _ -> }

        val result = source.load(loadParams())

        assertTrue("oczekiwano Error, było $result", result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `szukanie po ID — bramka Cloudflare daje blad, nie pusta liste`() = runTest {
        val capsRepository = mockk<CapsRepository>()
        coEvery { capsRepository.getById(any()) } throws httpError(403)
        val source = AdvancedSearchPagingSource(
            AdvancedSearchFilter(idValue = "150627"), capsRepository
        ) { _, _ -> }

        val result = source.load(loadParams())

        assertTrue("oczekiwano Error, było $result", result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `szukanie po ID — 404 daje pusta liste, bo takiego kapsla naprawde nie ma`() = runTest {
        val capsRepository = mockk<CapsRepository>()
        coEvery { capsRepository.getById(any()) } throws httpError(404)
        val source = AdvancedSearchPagingSource(
            AdvancedSearchFilter(idValue = "999999"), capsRepository
        ) { _, _ -> }

        val result = source.load(loadParams()) as PagingSource.LoadResult.Page

        assertEquals(emptyList<Cap>(), result.data)
    }

    @Test
    fun `anulowanie ladowania nie jest raportowane jako blad`() = runTest {
        // Zmiana filtru anuluje trwające ładowanie. CancellationException jest na JVM podklasą
        // IllegalStateException, więc catch (e: Exception) łapał je razem z resztą.
        val capsRepository = mockk<CapsRepository>()
        coEvery { capsRepository.advancedSearch(any(), any(), any(), any(), any()) } throws
            CancellationException("filtr zmieniony")
        val source = AdvancedSearchPagingSource(
            AdvancedSearchFilter(textValue = "tyskie"), capsRepository
        ) { _, _ -> }

        assertThrows(CancellationException::class.java) {
            runBlocking { source.load(loadParams()) }
        }
    }

    @Test
    fun `strona wyzerowana przez filtr client-side dobiera kolejna`() = runTest {
        // Bez dobierania użytkownik dostawał pustą listę mimo wyników na dalszych stronach.
        val capsRepository = mockk<CapsRepository>()
        val filter = AdvancedSearchFilter(countryId = 1, countryName = "Polska", onlyInCollection = true)
        coEvery { capsRepository.getByCountryId(1, 1) } returns Page(
            data = listOf(cap(1L, isInCollection = false)),
            lastPage = 3, currentPage = 1, perPage = Cap.PER_PAGE, total = 3
        )
        coEvery { capsRepository.getByCountryId(1, 2) } returns Page(
            data = listOf(cap(2L, isInCollection = true)),
            lastPage = 3, currentPage = 2, perPage = Cap.PER_PAGE, total = 3
        )
        val source = AdvancedSearchPagingSource(filter, capsRepository) { _, _ -> }

        val result = source.load(loadParams()) as PagingSource.LoadResult.Page

        assertEquals(1, result.data.size)
        assertEquals(2L, result.data[0].id)
        // Następny klucz musi wskazywać za ostatnią pobraną stronę, nie za żądaną.
        assertEquals(3, result.nextKey)
    }

    @Test
    fun `dobieranie stron zatrzymuje sie na ostatniej stronie`() = runTest {
        val capsRepository = mockk<CapsRepository>()
        val filter = AdvancedSearchFilter(countryId = 1, countryName = "Polska", onlyInCollection = true)
        coEvery { capsRepository.getByCountryId(1, any()) } returns Page(
            data = listOf(cap(1L, isInCollection = false)),
            lastPage = 1, currentPage = 1, perPage = Cap.PER_PAGE, total = 1
        )
        val source = AdvancedSearchPagingSource(filter, capsRepository) { _, _ -> }

        val result = source.load(loadParams()) as PagingSource.LoadResult.Page

        assertEquals(emptyList<Cap>(), result.data)
        assertEquals(null, result.nextKey)
    }
}

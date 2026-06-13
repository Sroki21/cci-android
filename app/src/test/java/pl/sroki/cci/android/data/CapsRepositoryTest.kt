package pl.sroki.cci.android.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.remote.CapApiService
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.Page

class CapsRepositoryTest {

    private lateinit var capApiService: CapApiService
    private lateinit var purchasedCapsLocalStore: PurchasedCapsLocalStore
    private lateinit var repository: CapsRepository

    private val fakePage = Page(
        data = listOf(
            Cap(id = 1L, country = "Poland", product = "Beer", liner = "Plastic",
                purpose = "Bottle closure", imageUrl = "https://example.com/1.jpg")
        ),
        lastPage = 1,
        currentPage = 1,
        perPage = 60,
        total = 1
    )

    @Before
    fun setUp() {
        capApiService = mockk()
        purchasedCapsLocalStore = mockk()
        repository = CapsRepository(capApiService, purchasedCapsLocalStore)
    }

    @Test
    fun `getLatest deleguje do capApiService z poprawna strona i perPage`() = runTest {
        coEvery { capApiService.getLatest(page = 1, perPage = Cap.PER_PAGE) } returns fakePage

        val result = repository.getLatest(page = 1)

        assertEquals(fakePage, result)
        coVerify(exactly = 1) { capApiService.getLatest(page = 1, perPage = Cap.PER_PAGE) }
    }

    @Test
    fun `getByCountryId deleguje do capApiService z poprawnym id`() = runTest {
        val countryId = 42
        coEvery {
            capApiService.getByCountryId(countryId = countryId, page = 1, perPage = Cap.PER_PAGE)
        } returns fakePage

        val result = repository.getByCountryId(id = countryId, page = 1)

        assertEquals(fakePage, result)
        coVerify(exactly = 1) {
            capApiService.getByCountryId(countryId = countryId, page = 1, perPage = Cap.PER_PAGE)
        }
    }

    @Test
    fun `getByQuery deleguje do capApiService z zapytaniem tekstowym`() = runTest {
        val query = "Tyskie"
        coEvery {
            capApiService.getByQuery(query = query, page = 1, perPage = Cap.PER_PAGE)
        } returns fakePage

        val result = repository.getByQuery(query = query, page = 1)

        assertEquals(fakePage, result)
        coVerify(exactly = 1) {
            capApiService.getByQuery(query = query, page = 1, perPage = Cap.PER_PAGE)
        }
    }

    @Test
    fun `latestCapsPagingSource tworzy nowe zrodlo paginacji`() {
        val source1 = repository.latestCapsPagingSource()
        val source2 = repository.latestCapsPagingSource()

        assert(source1 !== source2)
    }

    @Test
    fun `countryCapsPagingSource tworzy nowe zrodlo paginacji dla danego kraju`() {
        val source = repository.countryCapsPagingSource(id = 7)

        assert(source is CountryCapsPagingSource)
    }
}

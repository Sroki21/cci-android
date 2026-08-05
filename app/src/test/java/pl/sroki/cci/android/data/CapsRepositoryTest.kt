package pl.sroki.cci.android.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.remote.CapApiService
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapsSearchRequest
import pl.sroki.cci.android.model.Page
import pl.sroki.cci.android.model.SimilarCapsResponse

@OptIn(ExperimentalCoroutinesApi::class)
class CapsRepositoryTest {

    private lateinit var capApiService: CapApiService
    private lateinit var purchasedCapsLocalStore: PurchasedCapsLocalStore
    private lateinit var capPositionRepository: CapPositionRepository
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
        capPositionRepository = mockk()
        repository = CapsRepository(capApiService, purchasedCapsLocalStore, capPositionRepository)
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

    @Test
    fun `markPurchasedLocally dopisuje id i emituje collectionChanged`() = runTest {
        every { purchasedCapsLocalStore.add(1L) } returns Unit
        val received = async { repository.collectionChanged.first() }
        runCurrent()

        repository.markPurchasedLocally(1)

        received.await()
        verify(exactly = 1) { purchasedCapsLocalStore.add(1L) }
    }

    @Test
    fun `searchSimilar dopina isInCollection na podstawie przypisanych i zakupionych id`() = runTest {
        val part = mockk<MultipartBody.Part>()
        fun cap(id: Long) = Cap(
            id = id, country = "Poland", product = "Beer", liner = "Plastic",
            purpose = "Bottle closure", imageUrl = "https://example.com/$id.jpg"
        )
        coEvery { capApiService.searchSimilar(part) } returns
            SimilarCapsResponse(id = 1L, caps = listOf(cap(1L), cap(2L), cap(3L)))
        coEvery { capPositionRepository.getAllCapIds() } returns listOf(1L)
        every { purchasedCapsLocalStore.getIds() } returns setOf(2L)

        val result = repository.searchSimilar(part)

        assertTrue(result.caps.first { it.id == 1L }.isInCollection)
        assertTrue(result.caps.first { it.id == 2L }.isInCollection)
        assertFalse(result.caps.first { it.id == 3L }.isInCollection)
    }

    @Test
    fun `searchByFilter dopina isInCollection na podstawie przypisanych i zakupionych id`() = runTest {
        val request = CapsSearchRequest(producer = "Heineken")
        fun cap(id: Long) = Cap(
            id = id, country = "Poland", product = "Beer", liner = "Plastic",
            purpose = "Bottle closure", imageUrl = "https://example.com/$id.jpg"
        )
        coEvery { capApiService.searchCapsByFilter(request, 1, Cap.PER_PAGE) } returns Page(
            data = listOf(cap(1L), cap(2L)),
            lastPage = 1, currentPage = 1, perPage = Cap.PER_PAGE, total = 2
        )
        coEvery { capPositionRepository.getAllCapIds() } returns listOf(1L)
        every { purchasedCapsLocalStore.getIds() } returns emptySet()

        val result = repository.searchByFilter(request, page = 1)

        assertTrue(result.data.first { it.id == 1L }.isInCollection)
        assertFalse(result.data.first { it.id == 2L }.isInCollection)
    }
}

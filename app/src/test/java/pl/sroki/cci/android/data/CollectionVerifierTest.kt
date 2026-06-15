package pl.sroki.cci.android.data

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.Liner
import pl.sroki.cci.android.model.Product
import pl.sroki.cci.android.model.Purpose
import pl.sroki.cci.android.model.UserPublic
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

class CollectionVerifierTest {

    private lateinit var capsRepository: CapsRepository
    private lateinit var capCacheRepository: CapCacheRepository
    private lateinit var capPositionRepository: CapPositionRepository
    private lateinit var capPositionFirestoreService: CapPositionFirestoreService
    private lateinit var authManager: FirebaseAuthManager
    private lateinit var verifier: CollectionVerifier

    private val t1 = Instant.parse("2021-01-01T00:00:00Z")
    private val t2 = Instant.parse("2022-06-15T10:00:00Z")
    private val t3 = Instant.parse("2023-03-20T12:00:00Z")

    @Before
    fun setUp() {
        capsRepository = mockk()
        capCacheRepository = mockk()
        capPositionRepository = mockk()
        capPositionFirestoreService = mockk(relaxed = true)
        authManager = mockk()
        every { authManager.uid } returns MutableStateFlow(null)
        coEvery { capCacheRepository.markVerified(any(), any(), any()) } just Runs
        coEvery {
            capCacheRepository.upsertSnapshot(any(), any(), any(), any(), any(), any(), any())
        } just Runs
        verifier = CollectionVerifier(
            capsRepository, capCacheRepository, capPositionRepository,
            capPositionFirestoreService, authManager
        )
    }

    private fun capExtended(
        id: Int = 1,
        createdAt: Instant = t1,
        createdById: Int? = 42,
        updatedAt: Instant? = t2,
        description: String = "Test Cap",
        countryName: String = "Poland",
        imageUrl: String = "https://example.com/cap.jpg"
    ) = CapExtended(
        id = id,
        description = description,
        country = Country(1L, countryName, ""),
        product = Product(1, "Żywiec"),
        purpose = Purpose(1, "Beer"),
        liner = Liner(1, "PVC"),
        seriesSortOrder = null,
        series = null,
        periodUsed = null,
        year = null,
        imageUrl = imageUrl,
        usersCount = 0,
        createdBy = createdById?.let {
            UserPublic(it, "Jan", "Kowalski", "", true, Country(1L, "Poland", ""))
        },
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun capCache(
        capId: Long = 1L,
        createdAt: String? = t1.toString(),
        createdById: Int? = 42,
        updatedAt: String? = t2.toString(),
        name: String = "Test Cap",
        country: String = "Poland",
        imageUrl: String = "https://example.com/cap.jpg"
    ) = CapCache(
        capId = capId,
        createdAt = createdAt,
        createdById = createdById,
        updatedAt = updatedAt,
        name = name,
        country = country,
        imageUrl = imageUrl
    )

    @Test
    fun `verify — brak snapshotu (baseline) — zwraca OK i zapisuje snapshot`() = runTest {
        coEvery { capCacheRepository.getOne(1L) } returns null
        coEvery { capsRepository.getById(1) } returns capExtended()

        val result = verifier.verify(1L)

        assertEquals(CatalogStatus.OK, result)
        coVerify(exactly = 1) {
            capCacheRepository.upsertSnapshot(1L, any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `verify — fingerprint zgodny — zwraca OK`() = runTest {
        coEvery { capCacheRepository.getOne(1L) } returns capCache()
        coEvery { capsRepository.getById(1) } returns capExtended()

        val result = verifier.verify(1L)

        assertEquals(CatalogStatus.OK, result)
        coVerify(exactly = 0) {
            capCacheRepository.upsertSnapshot(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `verify — zmiana updatedAt — zwraca UPDATED`() = runTest {
        coEvery { capCacheRepository.getOne(1L) } returns capCache()
        coEvery { capsRepository.getById(1) } returns capExtended(updatedAt = t3)

        val result = verifier.verify(1L)

        assertEquals(CatalogStatus.UPDATED, result)
        coVerify(exactly = 0) {
            capCacheRepository.upsertSnapshot(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `verify — zmiana createdAt — zwraca SWAPPED`() = runTest {
        coEvery { capCacheRepository.getOne(1L) } returns capCache()
        coEvery { capsRepository.getById(1) } returns capExtended(createdAt = t3)

        val result = verifier.verify(1L)

        assertEquals(CatalogStatus.SWAPPED, result)
    }

    @Test
    fun `verify — 404 HTTP — zwraca MISSING`() = runTest {
        coEvery { capCacheRepository.getOne(1L) } returns capCache()
        val notFound = Response.error<Any>(
            404, "{}".toResponseBody("application/json".toMediaType())
        )
        coEvery { capsRepository.getById(1) } throws HttpException(notFound)

        val result = verifier.verify(1L)

        assertEquals(CatalogStatus.MISSING, result)
        coVerify(exactly = 1) { capCacheRepository.markVerified(1L, CatalogStatus.MISSING, any()) }
    }

    @Test
    fun `runFullScan — isCancelled po pierwszym elemencie — przetwarza co najwyzej 1`() = runTest {
        val ids = (1L..5L).toList()
        coEvery { capPositionRepository.getAllCapIds() } returns ids
        coEvery { capCacheRepository.getOne(any()) } returns null
        coEvery { capsRepository.getById(any()) } returns capExtended()

        val callCount = AtomicInteger(0)
        verifier.runFullScan(isCancelled = { callCount.incrementAndGet() > 1 })

        coVerify(atMost = 1) {
            capCacheRepository.upsertSnapshot(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `runBatch — max 4 rownolegle wywolania verify`() = runTest {
        val ids = (1L..8L).toList()
        coEvery { capPositionRepository.getAllCapIds() } returns ids
        coEvery { capCacheRepository.getOne(any()) } returns null

        val activeCalls = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        coEvery { capsRepository.getById(any()) } coAnswers {
            val current = activeCalls.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, current) }
            delay(1L)
            activeCalls.decrementAndGet()
            capExtended()
        }

        verifier.runFullScan()

        assertTrue("max concurrent calls: ${maxObserved.get()}", maxObserved.get() <= 4)
    }
}

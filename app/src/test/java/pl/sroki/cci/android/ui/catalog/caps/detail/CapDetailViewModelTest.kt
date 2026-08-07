package pl.sroki.cci.android.ui.catalog.caps.detail

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.PurchasedCapsLocalStore
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.BinderSuggestion
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.Liner
import pl.sroki.cci.android.model.Product
import pl.sroki.cci.android.model.Purpose
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.CatalogStatus
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CapDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var capsRepository: CapsRepository
    private lateinit var capPositionRepository: CapPositionRepository
    private lateinit var capCacheRepository: CapCacheRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var binderRepository: BinderRepository
    private lateinit var binderPageRepository: BinderPageRepository
    private lateinit var purchasedCapsLocalStore: PurchasedCapsLocalStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        capsRepository = mockk(relaxed = true)
        capPositionRepository = mockk(relaxed = true)
        capCacheRepository = mockk(relaxed = true)
        sessionRepository = mockk()
        binderRepository = mockk()
        binderPageRepository = mockk()
        purchasedCapsLocalStore = mockk(relaxed = true)

        every { sessionRepository.isLoggedIn } returns MutableStateFlow(false)
        every { binderRepository.getAll() } returns flowOf(emptyList())
        every { binderPageRepository.getByBinder(any()) } returns flowOf(emptyList())
        every { capPositionRepository.getByPage(any()) } returns flowOf(emptyList())
        coEvery { capPositionRepository.getAllCapIds() } returns emptyList()
        every { purchasedCapsLocalStore.getIds() } returns emptySet()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CapDetailViewModel(
        capsRepository,
        capPositionRepository,
        capCacheRepository,
        sessionRepository,
        binderRepository,
        binderPageRepository,
        purchasedCapsLocalStore,
    )

    // --- ładowanie szczegółów -------------------------------------------------------------

    @Test
    fun `kapsel usuniety z katalogu konczy sie ekranem bledu, nie wyjatkiem`() = runTest {
        coEvery { capsRepository.getById(1) } throws httpException(404)
        val viewModel = viewModel()

        viewModel.getCap(1)

        assertTrue(viewModel.capDetailUiState is CapDetailUiState.Error)
    }

    @Test
    fun `blad sieci konczy sie ekranem bledu`() = runTest {
        coEvery { capsRepository.getById(1) } throws IOException("brak sieci")
        val viewModel = viewModel()

        viewModel.getCap(1)

        assertTrue(viewModel.capDetailUiState is CapDetailUiState.Error)
    }

    @Test
    fun `rozjazd zapisany w Roomie nie wraca jako baner, gdy kapsel nie jest w klaserze`() = runTest {
        coEvery { capsRepository.getById(1) } returns cap(id = 1, isInCollection = true)
        coEvery { capPositionRepository.getBinderInfoByCapId(1L) } returns null
        coEvery { capCacheRepository.getOne(1L) } returns cachedCap(catalogStatus = CatalogStatus.SWAPPED)
        val viewModel = viewModel()

        viewModel.getCap(1)

        assertNull(viewModel.catalogStatus)
        assertTrue(viewModel.catalogChanges.isEmpty())
    }

    @Test
    fun `rozjazd kapsla w klaserze pokazuje baner`() = runTest {
        val viewModel = loadedInCollection()

        assertEquals(CatalogStatus.SWAPPED, viewModel.catalogStatus)
    }

    // --- odpięcie z rozjazdu --------------------------------------------------------------

    @Test
    fun `odpiecie z rozjazdu oddaje kapsel na liste zakupionych i gasi baner`() = runTest {
        val viewModel = loadedInCollection()

        viewModel.unlinkFlagged()

        coVerify(exactly = 1) { capPositionRepository.unassignToPurchased(1L) }
        coVerify(exactly = 1) { capCacheRepository.markVerified(1L, CatalogStatus.OK, any()) }
        assertEquals(CapStatus.PURCHASED, (viewModel.capDetailUiState as CapDetailUiState.Success).status)
        assertNull(viewModel.catalogStatus)
    }

    @Test
    fun `nieudane odpiecie zostawia kapsel w klaserze i pokazuje blad`() = runTest {
        val viewModel = loadedInCollection()
        coEvery { capPositionRepository.unassignToPurchased(1L) } throws IllegalStateException("baza padła")

        viewModel.unlinkFlagged()

        assertNotNull(viewModel.assignmentError)
        assertEquals(CapStatus.IN_COLLECTION, (viewModel.capDetailUiState as CapDetailUiState.Success).status)
        coVerify(exactly = 0) { capCacheRepository.markVerified(any(), CatalogStatus.OK, any()) }
    }

    // --- zmiana statusu -------------------------------------------------------------------

    @Test
    fun `nieudane wyjscie z kolekcji nie kasuje pozycji w klaserze ani wpisu zakupionych`() = runTest {
        val viewModel = loadedInCollection()
        coEvery { capsRepository.removeFromCollection(1) } throws IOException("HTTP 500")

        viewModel.setStatus(CapStatus.MISSING)

        coVerify(exactly = 0) { capPositionRepository.unassign(any()) }
        coVerify(exactly = 0) { capsRepository.markPurchasedLocally(any()) }
        assertEquals(CapStatus.IN_COLLECTION, (viewModel.capDetailUiState as CapDetailUiState.Success).status)
        assertNotNull(viewModel.assignmentError)
    }

    @Test
    fun `przejscie z klasera na zakupione odpina pozycje i dopisuje do zakupionych`() = runTest {
        val viewModel = loadedInCollection()

        viewModel.setStatus(CapStatus.PURCHASED)

        coVerify(exactly = 1) { capsRepository.markPurchasedLocally(1) }
        coVerify(exactly = 1) { capPositionRepository.unassign(1L) }
        coVerify(exactly = 0) { capsRepository.addToCollection(any()) }
        assertEquals(CapStatus.PURCHASED, (viewModel.capDetailUiState as CapDetailUiState.Success).status)
    }

    @Test
    fun `nieudane dodanie do kolekcji nie dopisuje kapsla do zakupionych`() = runTest {
        coEvery { capsRepository.getById(1) } returns cap(id = 1, isInCollection = false)
        coEvery { capPositionRepository.getBinderInfoByCapId(1L) } returns null
        coEvery { capCacheRepository.getOne(1L) } returns null
        coEvery { capsRepository.addToCollection(1) } throws IOException("HTTP 500")
        val viewModel = viewModel()
        viewModel.getCap(1)

        viewModel.setStatus(CapStatus.PURCHASED)

        coVerify(exactly = 0) { capsRepository.markPurchasedLocally(any()) }
        assertEquals(CapStatus.MISSING, (viewModel.capDetailUiState as CapDetailUiState.Success).status)
    }

    @Test
    fun `dodanie do kolekcji samo dopisuje do zakupionych, bez drugiego zapisu`() = runTest {
        coEvery { capsRepository.getById(1) } returns cap(id = 1, isInCollection = false)
        coEvery { capPositionRepository.getBinderInfoByCapId(1L) } returns null
        coEvery { capCacheRepository.getOne(1L) } returns null
        val viewModel = viewModel()
        viewModel.getCap(1)

        viewModel.setStatus(CapStatus.PURCHASED)

        coVerify(exactly = 1) { capsRepository.addToCollection(1) }
        coVerify(exactly = 0) { capsRepository.markPurchasedLocally(any()) }
        assertEquals(CapStatus.PURCHASED, (viewModel.capDetailUiState as CapDetailUiState.Success).status)
    }

    // --- sugestia klasera -----------------------------------------------------------------

    @Test
    fun `swiezo zalozony pusty klaser dostaje sugestie pierwszej pozycji`() = runTest {
        every { binderRepository.getAll() } returns flowOf(
            listOf(BinderView(1L, "Polska 1"), BinderView(2L, "Polska 2"))
        )
        every { binderPageRepository.getByBinder(2L) } returns flowOf(
            listOf(BinderPageView(id = 20L, binderId = 2L, pageNumber = 1))
        )
        coEvery { capsRepository.getById(1) } returns cap(id = 1, isInCollection = true)
        coEvery { capPositionRepository.getBinderInfoByCapId(1L) } returns null
        coEvery { capCacheRepository.getOne(1L) } returns null
        val viewModel = viewModel()

        viewModel.getCap(1)

        assertEquals(BinderSuggestion("Polska 2", 1, 1), viewModel.binderSuggestion)
    }

    @Test
    fun `zapelniona strona przesuwa sugestie na kolejna pozycje`() = runTest {
        every { binderRepository.getAll() } returns flowOf(listOf(BinderView(1L, "Polska 1")))
        every { binderPageRepository.getByBinder(1L) } returns flowOf(
            listOf(BinderPageView(id = 10L, binderId = 1L, pageNumber = 3))
        )
        every { capPositionRepository.getByPage(10L) } returns flowOf(
            listOf(slot(id = 100L, binderPageId = 10L, position = 7, capId = 55L))
        )
        coEvery { capsRepository.getById(1) } returns cap(id = 1, isInCollection = true)
        coEvery { capPositionRepository.getBinderInfoByCapId(1L) } returns null
        coEvery { capCacheRepository.getOne(1L) } returns null
        val viewModel = viewModel()

        viewModel.getCap(1)

        assertEquals(BinderSuggestion("Polska 1", 3, 8), viewModel.binderSuggestion)
    }

    // --- pomocnicze -----------------------------------------------------------------------

    /** Kapsel wstawiony w klaser, z rozjazdem „podmieniona tożsamość" zapisanym w Roomie. */
    private fun loadedInCollection(): CapDetailViewModel {
        coEvery { capsRepository.getById(1) } returns cap(id = 1, isInCollection = true)
        coEvery { capPositionRepository.getBinderInfoByCapId(1L) } returns
            CapBinderInfo(binderName = "Polska 1", pageNumber = 2, position = 5)
        coEvery { capCacheRepository.getOne(1L) } returns cachedCap(catalogStatus = CatalogStatus.SWAPPED)
        val viewModel = viewModel()
        viewModel.getCap(1)
        return viewModel
    }

    private fun httpException(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType()))
    )

    private fun slot(id: Long, binderPageId: Long, position: Int, capId: Long) =
        pl.sroki.cci.android.model.binder.CapSlot(id, binderPageId, position, capId)

    private fun cap(
        id: Int,
        isInCollection: Boolean,
        countryName: String = "Polska",
    ) = CapExtended(
        id = id,
        description = "Test Cap",
        country = Country(1L, countryName, ""),
        product = Product(1, "Żywiec"),
        purpose = Purpose(1, "Beer"),
        liner = Liner(1, "PVC"),
        seriesSortOrder = null,
        series = null,
        periodUsed = null,
        year = null,
        imageUrl = "https://example.com/cap.jpg",
        usersCount = 0,
        isInCollection = isInCollection,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-02-01T00:00:00Z"),
    )

    private fun cachedCap(catalogStatus: CatalogStatus) = CachedCap(
        capId = 1L,
        name = "Test Cap",
        country = "Polska",
        imageUrl = "https://example.com/cap.jpg",
        createdAt = "2024-01-01T00:00:00Z",
        createdById = 42,
        updatedAt = "2024-02-01T00:00:00Z",
        catalogStatus = catalogStatus,
        selectedProducerId = null,
        producer = "",
        imageUnavailable = false,
    )
}

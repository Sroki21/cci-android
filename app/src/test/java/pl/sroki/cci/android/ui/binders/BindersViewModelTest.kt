package pl.sroki.cci.android.ui.binders

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.Page
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.CapSlot
import pl.sroki.cci.android.model.binder.CatalogStatus

@OptIn(ExperimentalCoroutinesApi::class)
class BindersViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var binderRepository: BinderRepository
    private lateinit var binderPageRepository: BinderPageRepository
    private lateinit var capPositionRepository: CapPositionRepository
    private lateinit var capsRepository: CapsRepository
    private lateinit var countriesRepository: CountriesRepository
    private lateinit var capCacheRepository: CapCacheRepository
    private lateinit var collectionVerifier: CollectionVerifier
    private lateinit var viewModel: BindersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        binderRepository = mockk()
        binderPageRepository = mockk()
        capPositionRepository = mockk()
        capsRepository = mockk()
        countriesRepository = mockk()
        capCacheRepository = mockk(relaxed = true)
        collectionVerifier = mockk(relaxed = true)
        every { binderRepository.getAll() } returns flowOf(emptyList())
        every { binderPageRepository.getByBinder(any()) } returns flowOf(emptyList())
        every { capCacheRepository.flaggedCapsFlow() } returns flowOf(emptyList())
        coEvery { countriesRepository.getCountries() } returns emptyList()
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `createBinder_updatesState`() = runTest {
        val binder = BinderView(id = 1L, name = "Europa 1")
        every { binderRepository.getAll() } returns flowOf(listOf(binder))
        coEvery { binderRepository.create("Europa 1") } returns 1L
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        viewModel.createBinder("Europa 1")

        assertTrue(viewModel.uiState.value.binders.any { it.name == "Europa 1" })
    }

    @Test
    fun `createBinder_blankName_showsSnackbar`() = runTest {
        coEvery { binderRepository.create("") } throws IllegalArgumentException("Nazwa klasera nie może być pusta")

        viewModel.createBinder("")

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    @Test
    fun `deleteBinder_occupied_showsSnackbar`() = runTest {
        coEvery { binderRepository.delete(42L) } throws IllegalStateException("Klaser zawiera kapsle i nie może być usunięty")

        viewModel.requestDeleteBinder(42L)
        viewModel.confirmDeleteBinder()

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    @Test
    fun `addPage_atLimit_showsSnackbar`() = runTest {
        coEvery { binderPageRepository.addPage(1L) } throws IllegalStateException("Klaser może mieć maksymalnie 15 stron")

        viewModel.addPage(1L)

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    @Test
    fun `requestDeleteBinder_setsConfirmId`() = runTest {
        viewModel.requestDeleteBinder(42L)

        assertEquals(42L, viewModel.uiState.value.deleteBinderConfirmId)
    }

    @Test
    fun `requestRenamePage_setsTargetId`() = runTest {
        viewModel.requestRenamePage(7L)

        assertEquals(7L, viewModel.uiState.value.renamePageTargetId)
    }

    @Test
    fun `confirmRenamePage_success_clearsTargetId`() = runTest {
        coEvery { binderPageRepository.updatePageNumber(7L, 3) } returns Unit

        viewModel.requestRenamePage(7L)
        viewModel.confirmRenamePage(3)

        assertEquals(null, viewModel.uiState.value.renamePageTargetId)
    }

    @Test
    fun `confirmRenamePage_duplicateNumber_showsSnackbar`() = runTest {
        coEvery { binderPageRepository.updatePageNumber(7L, 3) } throws
            IllegalStateException("Strona o numerze 3 już istnieje w tym klaserze")

        viewModel.requestRenamePage(7L)
        viewModel.confirmRenamePage(3)

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    /**
     * Przeniesienie strony do innego klasera wywołuje emisje w dwóch niezależnych Flow — klasera
     * źródłowego i docelowego — a ich kolejność jest niedeterministyczna. Oba testy odgrywają
     * po jednej kolejności; sterują nią ręcznie, bo w prawdziwym Room wyjdzie raz tak, raz tak.
     */
    @Test
    fun `movePage_docelowyEmitujePierwszy_stronaZachowujeKapsle`() = runTest {
        val (sourcePages, targetPages) = setUpMoveScenario()

        targetPages.value = listOf(PAGE.copy(binderId = TARGET_BINDER_ID))
        sourcePages.value = emptyList()

        assertEquals(listOf(SLOT), viewModel.uiState.value.capPositions[PAGE_ID])
    }

    @Test
    fun `movePage_zrodlowyEmitujePierwszy_stronaZachowujeKapsle`() = runTest {
        val (sourcePages, targetPages) = setUpMoveScenario()

        sourcePages.value = emptyList()
        targetPages.value = listOf(PAGE.copy(binderId = TARGET_BINDER_ID))

        assertEquals(listOf(SLOT), viewModel.uiState.value.capPositions[PAGE_ID])
    }

    private fun setUpMoveScenario(): Pair<MutableStateFlow<List<BinderPageView>>, MutableStateFlow<List<BinderPageView>>> {
        val sourcePages = MutableStateFlow(listOf(PAGE))
        val targetPages = MutableStateFlow(emptyList<BinderPageView>())
        every { binderRepository.getAll() } returns flowOf(
            listOf(
                BinderView(id = SOURCE_BINDER_ID, name = "Źródłowy"),
                BinderView(id = TARGET_BINDER_ID, name = "Docelowy")
            )
        )
        every { binderPageRepository.getByBinder(SOURCE_BINDER_ID) } returns sourcePages
        every { binderPageRepository.getByBinder(TARGET_BINDER_ID) } returns targetPages
        every { capPositionRepository.getByPage(PAGE_ID) } returns MutableStateFlow(listOf(SLOT))
        coEvery { capCacheRepository.getByIds(any()) } returns emptyList()
        coEvery { capsRepository.searchByFilter(any(), any()) } returns emptyPage()
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        assertEquals(listOf(SLOT), viewModel.uiState.value.capPositions[PAGE_ID])
        return sourcePages to targetPages
    }

    @Test
    fun `flaggedCapIds_pochodzaZFlowCache`() = runTest {
        every { capCacheRepository.flaggedCapsFlow() } returns flowOf(listOf(cachedCap(capId = 55L)))
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        assertEquals(setOf(55L), viewModel.uiState.value.flaggedCapIds)
    }

    @Test
    fun `loadCapInfo_odpowiedzBezZdjecia_zapisujeZnacznikIPrzestajePytac`() = runTest {
        val positions = MutableStateFlow(listOf(SLOT))
        setUpSingleBinder(positions)
        coEvery { capCacheRepository.getByIds(any()) } returns emptyList()
        coEvery { capsRepository.searchByFilter(any(), any()) } returns
            emptyPage().copy(data = listOf(cap(id = CAP_ID, imageUrl = "")))
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        coVerify(exactly = 1) { capCacheRepository.markImageUnavailable(CAP_ID) }

        // Kolejna emisja tej samej strony nie może wywołać kolejnego zapytania do API.
        positions.value = listOf(SLOT.copy(position = 2))

        coVerify(exactly = 1) { capsRepository.searchByFilter(any(), any()) }
    }

    /**
     * `cap_position` nie ma unikalności na `cap_id`, więc ten sam kapsel może leżeć na dwóch
     * stronach. Każda strona ma własny, równoległy kolektor pozycji, a wpis w capInfoCache
     * pojawia się dopiero po powrocie z API — bez zbioru zapytań w locie oba kolektory pytały
     * o ten sam kapsel osobno.
     */
    @Test
    fun `loadCapInfo_tenSamKapselNaDwochStronach_jednoZapytanieDoApi`() = runTest {
        val secondPageId = 11L
        every { binderRepository.getAll() } returns
            flowOf(listOf(BinderView(id = SOURCE_BINDER_ID, name = "Klaser")))
        every { binderPageRepository.getByBinder(SOURCE_BINDER_ID) } returns MutableStateFlow(
            listOf(PAGE, BinderPageView(id = secondPageId, binderId = SOURCE_BINDER_ID, pageNumber = 2))
        )
        every { capPositionRepository.getByPage(PAGE_ID) } returns MutableStateFlow(listOf(SLOT))
        every { capPositionRepository.getByPage(secondPageId) } returns MutableStateFlow(
            listOf(SLOT.copy(id = 101L, binderPageId = secondPageId))
        )
        coEvery { capCacheRepository.getByIds(any()) } returns emptyList()
        coEvery { capsRepository.searchByFilter(any(), any()) } coAnswers {
            delay(100)
            emptyPage().copy(data = listOf(cap(id = CAP_ID, imageUrl = "https://obraz")))
        }
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        advanceTimeBy(200)

        coVerify(exactly = 1) { capsRepository.searchByFilter(any(), any()) }
    }

    @Test
    fun `loadCapInfo_bladSieci_nieZapisujeZnacznika`() = runTest {
        setUpSingleBinder(MutableStateFlow(listOf(SLOT)))
        coEvery { capCacheRepository.getByIds(any()) } returns emptyList()
        coEvery { capsRepository.searchByFilter(any(), any()) } throws java.io.IOException("brak sieci")
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        coVerify(exactly = 0) { capCacheRepository.markImageUnavailable(any()) }
    }

    @Test
    fun `loadCapInfo_znacznikWCache_pomijaZapytanieDoApi`() = runTest {
        setUpSingleBinder(MutableStateFlow(listOf(SLOT)))
        coEvery { capCacheRepository.getByIds(any()) } returns
            listOf(cachedCap(capId = CAP_ID, imageUnavailable = true))
        viewModel = BindersViewModel(binderRepository, binderPageRepository, capPositionRepository, capsRepository, countriesRepository, capCacheRepository, collectionVerifier)

        coVerify(exactly = 0) { capsRepository.searchByFilter(any(), any()) }
    }

    private fun setUpSingleBinder(positions: MutableStateFlow<List<CapSlot>>) {
        every { binderRepository.getAll() } returns
            flowOf(listOf(BinderView(id = SOURCE_BINDER_ID, name = "Klaser")))
        every { binderPageRepository.getByBinder(SOURCE_BINDER_ID) } returns MutableStateFlow(listOf(PAGE))
        every { capPositionRepository.getByPage(PAGE_ID) } returns positions
    }

    private fun emptyPage() = Page<Cap>(data = emptyList(), lastPage = 1, currentPage = 1, perPage = 60, total = 0)

    private fun cap(id: Long, imageUrl: String) = Cap(
        id = id,
        country = "Polska",
        product = "",
        liner = "",
        purpose = "",
        imageUrl = imageUrl
    )

    private fun cachedCap(capId: Long, imageUnavailable: Boolean = false) = CachedCap(
        capId = capId,
        name = "",
        country = "Polska",
        imageUrl = "",
        createdAt = null,
        createdById = null,
        updatedAt = null,
        catalogStatus = CatalogStatus.UNKNOWN,
        selectedProducerId = null,
        producer = "",
        imageUnavailable = imageUnavailable
    )

    private companion object {
        const val SOURCE_BINDER_ID = 1L
        const val TARGET_BINDER_ID = 2L
        const val PAGE_ID = 10L
        const val CAP_ID = 99L
        val PAGE = BinderPageView(id = PAGE_ID, binderId = SOURCE_BINDER_ID, pageNumber = 1)
        val SLOT = CapSlot(id = 100L, binderPageId = PAGE_ID, position = 1, capId = CAP_ID)
    }
}

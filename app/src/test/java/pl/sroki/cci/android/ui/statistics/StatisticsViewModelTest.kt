package pl.sroki.cci.android.ui.statistics

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.model.CountryStatRow
import pl.sroki.cci.android.model.CapExtended
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var capsRepository: CapsRepository
    private lateinit var capPositionRepository: CapPositionRepository
    private lateinit var capCacheRepository: CapCacheRepository
    private lateinit var countriesRepository: CountriesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        capsRepository = mockk(relaxed = true)
        capPositionRepository = mockk(relaxed = true)
        capCacheRepository = mockk(relaxed = true)
        countriesRepository = mockk(relaxed = true)
        coEvery { capPositionRepository.getTotalCount() } returns 42
        coEvery { capCacheRepository.getCountryStats() } returns
            listOf(CountryStatRow("Polska", 30), CountryStatRow("Belgia", 12))
        coEvery { capCacheRepository.getMissingForPositioned() } returns emptyList()
        coEvery { countriesRepository.getFlagMap() } returns mapOf("Polska" to "https://flag/pl.png")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = StatisticsViewModel(
        capsRepository,
        capPositionRepository,
        capCacheRepository,
        countriesRepository,
    )

    /** Kapsel, którego pobranie zajmuje [durationMs] wirtualnego czasu. */
    private fun slowCap(durationMs: Long) {
        val cap = mockk<CapExtended>(relaxed = true)
        every { cap.country } returns mockk(relaxed = true) { every { name } returns "Polska" }
        every { cap.imageUrl } returns "https://img/cap.jpg"
        coEvery { capsRepository.getById(any()) } coAnswers {
            delay(durationMs)
            cap
        }
    }

    @Test
    fun `laduje statystyki z cache i flagami`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value as StatisticsUiState.Success
        assertEquals(42, state.totalCaps)
        assertEquals(2, state.totalCountries)
        assertEquals(listOf("Polska", "Belgia"), state.topCountries.map { it.name })
        assertEquals("https://flag/pl.png", state.topCountries.first().flagUrl)
    }

    @Test
    fun `awaria pobrania flag nie wywala ekranu w stan Error`() = runTest(testDispatcher) {
        // Flagi to dodatek — statystyki muszą się pokazać także bez nich.
        coEvery { countriesRepository.getFlagMap() } throws IOException("brak sieci")
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("oczekiwano Success, było $state", state is StatisticsUiState.Success)
        assertEquals(null, (state as StatisticsUiState.Success).topCountries.first().flagUrl)
    }

    @Test
    fun `awaria odczytu statystyk konczy sie stanem Error`() = runTest(testDispatcher) {
        coEvery { capCacheRepository.getCountryStats() } throws IOException("baza padla")
        val viewModel = viewModel()

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is StatisticsUiState.Error)
    }

    @Test
    fun `zaden kapsel z batcha nie wygasa przez czekanie na semafor`() = runTest(testDispatcher) {
        // Batch to 30 id na 15 permitów, a pobranie trwa 5 s przy limicie 6 s. Gdy timeout
        // obejmował czekanie na permit, druga piętnastka wygasała nie zdążywszy wystartować
        // i te kraje po cichu znikały ze statystyk.
        coEvery { capCacheRepository.getMissingForPositioned() } returns (1L..30L).toList()
        slowCap(durationMs = 5_000)
        val viewModel = viewModel()

        advanceUntilIdle()

        coVerify(exactly = 30) { capCacheRepository.upsertFull(any(), "Polska", any()) }
    }

    @Test
    fun `ponowne load anuluje poprzedni przebieg`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        // Drugi load zanim pierwszy (z init) zdążył wystartować — ma zostać jeden przebieg.
        viewModel.load()
        advanceUntilIdle()

        coVerify(exactly = 2) { capCacheRepository.getCountryStats() }
    }
}

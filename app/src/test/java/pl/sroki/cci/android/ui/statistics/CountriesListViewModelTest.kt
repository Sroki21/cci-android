package pl.sroki.cci.android.ui.statistics

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.model.CountryStatRow
import java.io.IOException

/**
 * Ani CountriesListViewModel.load(), ani CountryOwnedCapsViewModel.load() nie miały try/catch —
 * wyjątek zabijał korutynę, isLoading zostawało true i ekran wisiał na spinnerze bez końca
 * i bez słowa wyjaśnienia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CountriesListViewModelTest {

    private lateinit var capCacheRepository: CapCacheRepository
    private lateinit var countriesRepository: CountriesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        capCacheRepository = mockk(relaxed = true)
        countriesRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CountriesListViewModel(capCacheRepository, countriesRepository)

    @Test
    fun `blad wczytywania konczy ladowanie i zglasza komunikat`() = runTest {
        coEvery { countriesRepository.getFlagMap() } throws IOException("Brak połączenia")

        val vm = viewModel()

        assertFalse("spinner musi zgasnąć", vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `blad bez komunikatu tez daje czytelny tekst`() = runTest {
        coEvery { countriesRepository.getFlagMap() } throws IOException()

        val vm = viewModel()

        assertEquals("Nie udało się wczytać krajów", vm.uiState.value.error)
    }

    @Test
    fun `udane wczytanie nie ustawia bledu`() = runTest {
        coEvery { countriesRepository.getFlagMap() } returns mapOf("Polska" to "flaga.png")
        coEvery { capCacheRepository.getCountryStats() } returns
            listOf(CountryStatRow(country = "Polska", count = 120))

        val vm = viewModel()

        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.countries.size)
    }

    @Test
    fun `ponowienie po bledzie wczytuje liste`() = runTest {
        coEvery { countriesRepository.getFlagMap() } throws IOException("Brak połączenia")
        val vm = viewModel()
        assertNotNull(vm.uiState.value.error)

        coEvery { countriesRepository.getFlagMap() } returns emptyMap()
        coEvery { capCacheRepository.getCountryStats() } returns
            listOf(CountryStatRow(country = "Belgia", count = 12))
        vm.retry()

        assertNull(vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.countries.size)
    }
}

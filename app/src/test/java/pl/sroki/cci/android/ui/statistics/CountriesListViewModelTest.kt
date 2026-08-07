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
import pl.sroki.cci.android.model.binder.CountryCapCount
import java.io.IOException

/**
 * Ani CountriesListViewModel.load(), ani CountryOwnedCapsViewModel.load() nie miały try/catch —
 * wyjątek zabijał korutynę, isLoading zostawało true i ekran wisiał na spinnerze bez końca
 * i bez słowa wyjaśnienia.
 *
 * getFlagMap() jest osobno objęty runCatching: flagi to dodatek z sieci, a lista krajów liczy
 * się w całości z lokalnego Roomu (capCacheRepository.getCountryStats()) — jego błąd nie może
 * przesłonić realnych danych o kolekcji.
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
    fun `blad lokalnych statystyk konczy ladowanie i zglasza komunikat`() = runTest {
        coEvery { countriesRepository.getFlagMap() } returns emptyMap()
        coEvery { capCacheRepository.getCountryStats() } throws IOException("Brak połączenia")

        val vm = viewModel()

        assertFalse("spinner musi zgasnąć", vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `blad bez komunikatu tez daje czytelny tekst`() = runTest {
        coEvery { countriesRepository.getFlagMap() } returns emptyMap()
        coEvery { capCacheRepository.getCountryStats() } throws IOException()

        val vm = viewModel()

        assertEquals("Nie udało się wczytać krajów", vm.uiState.value.error)
    }

    @Test
    fun `blad getFlagMap nie przeslania listy z lokalnego Roomu`() = runTest {
        coEvery { countriesRepository.getFlagMap() } throws IOException("Brak połączenia")
        coEvery { capCacheRepository.getCountryStats() } returns
            listOf(CountryCapCount(country = "Polska", count = 120))

        val vm = viewModel()

        assertNull("flagi to dodatek — ich brak nie może dać ekranu błędu", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.countries.size)
        assertNull("brak flagi przy nieudanym pobraniu", vm.uiState.value.countries.first().flagUrl)
    }

    @Test
    fun `udane wczytanie nie ustawia bledu`() = runTest {
        coEvery { countriesRepository.getFlagMap() } returns mapOf("Polska" to "flaga.png")
        coEvery { capCacheRepository.getCountryStats() } returns
            listOf(CountryCapCount(country = "Polska", count = 120))

        val vm = viewModel()

        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.countries.size)
    }

    @Test
    fun `ponowienie po bledzie wczytuje liste`() = runTest {
        coEvery { countriesRepository.getFlagMap() } returns emptyMap()
        coEvery { capCacheRepository.getCountryStats() } throws IOException("Brak połączenia")
        val vm = viewModel()
        assertNotNull(vm.uiState.value.error)

        coEvery { capCacheRepository.getCountryStats() } returns
            listOf(CountryCapCount(country = "Belgia", count = 12))
        vm.retry()

        assertNull(vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.countries.size)
    }
}

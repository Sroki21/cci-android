package pl.sroki.cci.android.ui.catalog.caps.advanced

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.AdvancedSearchPagingSource
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.ProducersRepository
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.model.AdvancedSearchFilter

/**
 * Fabryka PagingSource czytała pole `filter`, zmieniane przy każdym dotknięciu formularza,
 * zamiast filtra zatwierdzonego przyciskiem „Szukaj". Gdy przyszło collectionChanged (dodanie
 * kapsla do kolekcji unieważnia listę), Pager tworzył źródło z BIEŻĄCĄ zawartością formularza
 * i wyniki przeskakiwały na filtr, którego użytkownik nigdy nie zatwierdził.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedSearchViewModelTest {

    private lateinit var capsRepository: CapsRepository
    private lateinit var collectionChanged: MutableSharedFlow<Unit>
    private lateinit var uzyteFiltry: MutableList<AdvancedSearchFilter>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        collectionChanged = MutableSharedFlow(extraBufferCapacity = 1)
        uzyteFiltry = mutableListOf()

        // relaxed, bo prawdziwy AdvancedSearchPagingSource sięgnie po nim do API przy ładowaniu —
        // treść wyników nie ma tu znaczenia, liczy się filtr, z jakim źródło powstało.
        capsRepository = mockk(relaxed = true)
        every { capsRepository.collectionChanged } returns collectionChanged
        val filtr = slot<AdvancedSearchFilter>()
        val callback = slot<(Int, Int?) -> Unit>()
        // Prawdziwe źródło, nie mock: invalidate() jest w PagingSource finalne, a to właśnie ono
        // każe Pagerowi zawołać fabrykę ponownie — czyli odtworzyć badany scenariusz.
        every {
            capsRepository.advancedSearchPagingSource(capture(filtr), capture(callback))
        } answers {
            uzyteFiltry += filtr.captured
            ostatniCallback = callback.captured
            AdvancedSearchPagingSource(filtr.captured, capsRepository, callback.captured)
        }
    }

    private var ostatniCallback: ((Int, Int?) -> Unit)? = null

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): AdvancedSearchViewModel {
        val countriesRepository = mockk<CountriesRepository>()
        coEvery { countriesRepository.getCountries() } returns emptyList()
        val sessionRepository = mockk<SessionRepository>()
        every { sessionRepository.isLoggedIn } returns MutableStateFlow(true)
        return AdvancedSearchViewModel(
            capsRepository = capsRepository,
            countriesRepository = countriesRepository,
            producersRepository = mockk<ProducersRepository>(relaxed = true),
            sessionRepository = sessionRepository,
            savedStateHandle = SavedStateHandle()
        )
    }

    /** Sedno N1: odświeżenie listy musi trzymać się filtra zatwierdzonego przyciskiem. */
    @Test
    fun `odswiezenie listy uzywa zatwierdzonego filtra, nie zawartosci formularza`() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.caps.collect { } }
        runCurrent()

        vm.updateFilter(AdvancedSearchFilter(countryName = "Polska", countryId = 1))
        vm.search()
        runCurrent()

        // Użytkownik grzebie w formularzu, ale NIE klika „Szukaj".
        vm.updateFilter(AdvancedSearchFilter(countryName = "Niemcy", countryId = 2))
        // ...i dodaje kapsel do kolekcji, co unieważnia listę.
        collectionChanged.emit(Unit)
        runCurrent()

        assertEquals("Polska", uzyteFiltry.last().countryName)
    }

    @Test
    fun `klikniecie Szukaj zatwierdza biezacy formularz`() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.caps.collect { } }
        runCurrent()

        vm.updateFilter(AdvancedSearchFilter(countryName = "Polska", countryId = 1))
        vm.search()
        vm.updateFilter(AdvancedSearchFilter(countryName = "Niemcy", countryId = 2))
        vm.search()
        runCurrent()

        assertEquals("Niemcy", uzyteFiltry.last().countryName)
    }

    /** N2: licznik „Znaleziono" doliczał strony do starej sumy przy każdym odświeżeniu. */
    @Test
    fun `licznik wynikow zeruje sie przy odswiezeniu listy`() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.caps.collect { } }
        runCurrent()
        vm.updateFilter(AdvancedSearchFilter(textValue = "heineken"))
        vm.search()
        runCurrent()

        // API nie podało sumy, więc licznik rośnie o rozmiar strony.
        ostatniCallback!!.invoke(60, null)
        assertEquals(60, vm.totalResults.value)

        // Zmiana kolekcji unieważnia listę: Pager buduje źródło od nowa, więc suma też musi
        // startować od zera. Wcześniej zerowanie było tylko w search() i strony doliczały się
        // do starej wartości — „Znaleziono: N" rosło przy każdej zmianie statusu kapsla.
        collectionChanged.emit(Unit)
        runCurrent()

        assertEquals(null, vm.totalResults.value)
    }
}

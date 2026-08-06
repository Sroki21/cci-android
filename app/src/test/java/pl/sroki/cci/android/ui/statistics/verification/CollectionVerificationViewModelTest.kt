package pl.sroki.cci.android.ui.statistics.verification

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.data.ScanOutcome

/**
 * Ręczny skan wyrzucał ScanOutcome do kosza. Gdy katalog był nieosiągalny (offline, 403, bramka
 * Cloudflare), wszystkie verify() padały, runFullScan kończył się „normalnie", pasek dochodził do
 * końca i ekran pokazywał „Brak rozjazdów" — nieodróżnialnie od skanu, który naprawdę wszystko
 * sprawdził. Ten sam błąd naprawiono wcześniej w HomeViewModel (663122d), ścieżkę ręczną pominięto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionVerificationViewModelTest {

    private lateinit var verifier: CollectionVerifier
    private lateinit var capCacheRepository: CapCacheRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        verifier = mockk(relaxed = true)
        capCacheRepository = mockk(relaxed = true)
        every { capCacheRepository.flaggedCapsFlow() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CollectionVerificationViewModel(
        capCacheRepository = capCacheRepository,
        capPositionRepository = mockk<CapPositionRepository>(relaxed = true),
        collectionVerifier = verifier
    )

    @Test
    fun `skan bez ani jednej odpowiedzi katalogu jest zglaszany jako nieudany`() = runTest {
        coEvery { verifier.runFullScan(any(), any()) } returns ScanOutcome(total = 120, reachedCatalog = 0)

        val vm = viewModel()
        vm.runFullScan()

        assertTrue(vm.scan.value.failed)
        assertFalse(vm.scan.value.running)
    }

    @Test
    fun `udany skan nie zglasza awarii`() = runTest {
        coEvery { verifier.runFullScan(any(), any()) } returns ScanOutcome(total = 120, reachedCatalog = 120)

        val vm = viewModel()
        vm.runFullScan()

        assertFalse(vm.scan.value.failed)
    }

    /** Pusta kolekcja: nie ma czego skanować, więc zero odpowiedzi katalogu to nie awaria. */
    @Test
    fun `pusta kolekcja nie jest awaria`() = runTest {
        coEvery { verifier.runFullScan(any(), any()) } returns ScanOutcome(total = 0, reachedCatalog = 0)

        val vm = viewModel()
        vm.runFullScan()

        assertFalse(vm.scan.value.failed)
    }

    @Test
    fun `wyjatek ze skanu jest zglaszany jako nieudany skan`() = runTest {
        coEvery { verifier.runFullScan(any(), any()) } throws RuntimeException("sieć leży")

        val vm = viewModel()
        vm.runFullScan()

        assertTrue(vm.scan.value.failed)
        assertFalse(vm.scan.value.running)
    }

    /** Anulowanie to decyzja użytkownika, nie awaria — mimo że przerwany skan też nie dotyka katalogu. */
    @Test
    fun `anulowany skan nie jest zglaszany jako awaria`() = runTest {
        var vm: CollectionVerificationViewModel? = null
        // Użytkownik klika „Anuluj" w trakcie skanu; przerwany skan nie dotknie katalogu,
        // więc outcome wygląda tak samo jak przy leżącej sieci — a awarią nie jest.
        coEvery { verifier.runFullScan(any(), any()) } coAnswers {
            vm!!.cancelScan()
            ScanOutcome(total = 120, reachedCatalog = 0)
        }

        vm = viewModel()
        vm.runFullScan()

        assertFalse(vm.scan.value.failed)
    }

    /**
     * W2: strażnik `if (_scan.value.running) return` stał przed launch, ale flaga podnosiła się
     * dopiero w środku korutyny — dwa szybkie kliknięcia widziały running == false i odpalały
     * dwa równoległe skany całej kolekcji.
     */
    @Test
    fun `podwojne klikniecie nie odpala drugiego skanu`() = runTest {
        // Standardowy dispatcher: korutyna czeka na wznowienie, więc oba wywołania trafiają
        // w ten sam moment, w którym wcześniej flaga jeszcze nie była podniesiona.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { verifier.runFullScan(any(), any()) } returns ScanOutcome(total = 120, reachedCatalog = 120)

        val vm = viewModel()
        vm.runFullScan()
        vm.runFullScan()
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { verifier.runFullScan(any(), any()) }
    }
}

package pl.sroki.cci.android.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.remote.firestore.PurchasedCapsFirestoreService

/**
 * Wypchnięcie lokalnej listy "zakupionych" do chmury musi być jednorazowe.
 *
 * Gałąź backfillu odpalała przy każdym starcie, ilekroć chmura była pusta — i sama cofała
 * usunięcia, przed którymi miała bronić rezygnacja ze scalania zbiorów. Wyczyszczenie listy na
 * urządzeniu B opróżniało chmurę (`remove()` pisze dwutorowo), urządzenie A wypychało przy starcie
 * swoją nieaktualną kopię, a B odtwarzało ją z chmury: skasowane kapsle wracały po dwóch
 * uruchomieniach.
 */
class FirestoreSyncPurchasedTest {

    private val uid = "uid-testowy"

    private lateinit var zapisaneWersje: MutableMap<String, Int>
    private lateinit var purchasedCapsService: PurchasedCapsFirestoreService
    private lateinit var localStore: PurchasedCapsLocalStore
    private lateinit var capPositionDao: CapPositionDao
    private lateinit var useCase: FirestoreRestoreUseCase

    @Before
    fun setUp() {
        zapisaneWersje = mutableMapOf()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val klucz = slot<String>()
        val wartosc = slot<Int>()
        every { editor.putInt(capture(klucz), capture(wartosc)) } answers {
            zapisaneWersje[klucz.captured] = wartosc.captured
            editor
        }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(any(), any()) } answers { zapisaneWersje[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        val authManager = mockk<FirebaseAuthManager>(relaxed = true)
        every { authManager.uid } returns MutableStateFlow(uid)

        purchasedCapsService = mockk(relaxed = true)
        localStore = mockk(relaxed = true)
        capPositionDao = mockk(relaxed = true)
        // Bez przypiętych kapsli prunePurchasedAlreadyInBinders kończy od razu i nie miesza w asercjach.
        coEvery { capPositionDao.getAllCapIds() } returns emptyList()

        useCase = FirestoreRestoreUseCase(
            context = context,
            authManager = authManager,
            database = mockk(relaxed = true),
            binderDao = mockk(relaxed = true),
            binderPageDao = mockk(relaxed = true),
            capPositionDao = capPositionDao,
            capCacheDao = mockk(relaxed = true),
            binderService = mockk(relaxed = true),
            binderPageService = mockk(relaxed = true),
            capPositionService = mockk(relaxed = true),
            purchasedCapsService = purchasedCapsService,
            purchasedCapsLocalStore = localStore
        )
    }

    private fun stan(lokalne: Set<Long>, zdalne: Set<Long>) {
        every { localStore.isEmpty() } returns lokalne.isEmpty()
        every { localStore.getIds() } returns lokalne
        coEvery { purchasedCapsService.fetch(uid) } returns zdalne
    }

    @Test
    fun `pierwsze uruchomienie wypycha lokalna liste do pustej chmury`() = runTest {
        stan(lokalne = setOf(1L, 2L, 3L), zdalne = emptySet())

        useCase.syncPurchasedCaps()

        coVerify(exactly = 1) { purchasedCapsService.scheduleReplaceAll(uid, setOf(1L, 2L, 3L)) }
    }

    /** Sedno regresji: druga runda to już "skasowane w chmurze", a nie "jeszcze nie wypchnięte". */
    @Test
    fun `drugie uruchomienie nie wypycha ponownie mimo pustej chmury`() = runTest {
        stan(lokalne = setOf(1L, 2L, 3L), zdalne = emptySet())

        useCase.syncPurchasedCaps()
        useCase.syncPurchasedCaps()

        coVerify(exactly = 1) { purchasedCapsService.scheduleReplaceAll(any(), any()) }
    }

    /**
     * Najważniejszy przypadek: urządzenie, które nigdy nie potrzebowało backfillu, też musi
     * zapamiętać, że etap ma za sobą. Inaczej flaga zostaje uzbrojona i pierwsze wyczyszczenie
     * listy na innym urządzeniu wskrzesza tu całą kolekcję.
     */
    @Test
    fun `po zwyklym starcie z niepusta chmura pozniejsze wyczyszczenie nie wskrzesza listy`() = runTest {
        stan(lokalne = setOf(1L, 2L, 3L), zdalne = setOf(1L, 2L, 3L))
        useCase.syncPurchasedCaps()

        // Na innym urządzeniu wszystko skasowane — chmura pusta, tutaj lista jeszcze jest.
        stan(lokalne = setOf(1L, 2L, 3L), zdalne = emptySet())
        useCase.syncPurchasedCaps()

        coVerify(exactly = 0) { purchasedCapsService.scheduleReplaceAll(any(), any()) }
    }

    @Test
    fun `swieza instalacja odtwarza liste z chmury`() = runTest {
        stan(lokalne = emptySet(), zdalne = setOf(7L, 8L))

        useCase.syncPurchasedCaps()

        verify(exactly = 1) { localStore.replaceAllLocally(setOf(7L, 8L)) }
        coVerify(exactly = 0) { purchasedCapsService.scheduleReplaceAll(any(), any()) }
    }

    /** Odtwarzanie z chmury zostaje bez zmian — nie chowamy go za flagą backfillu. */
    @Test
    fun `odtwarzanie z chmury dziala takze po ustawieniu flagi`() = runTest {
        stan(lokalne = setOf(1L), zdalne = setOf(1L))
        useCase.syncPurchasedCaps()

        // Reinstalacja: lokalnie pusto, w chmurze komplet.
        stan(lokalne = emptySet(), zdalne = setOf(1L, 2L))
        useCase.syncPurchasedCaps()

        verify(exactly = 1) { localStore.replaceAllLocally(setOf(1L, 2L)) }
    }

    /**
     * Nieudany odczyt z chmury nie może zużyć jednorazowej szansy na backfill — inaczej jedna
     * awaria sieci przy pierwszym starcie po aktualizacji kasowałaby migrację listy na zawsze.
     */
    @Test
    fun `bledny odczyt z chmury nie zuzywa flagi`() = runTest {
        every { localStore.isEmpty() } returns false
        every { localStore.getIds() } returns setOf(1L, 2L)
        coEvery { purchasedCapsService.fetch(uid) } throws IllegalStateException("brak sieci")
        useCase.syncPurchasedCaps()

        stan(lokalne = setOf(1L, 2L), zdalne = emptySet())
        useCase.syncPurchasedCaps()

        coVerify(exactly = 1) { purchasedCapsService.scheduleReplaceAll(uid, setOf(1L, 2L)) }
    }

    @Test
    fun `obie strony puste nie robia nic`() = runTest {
        stan(lokalne = emptySet(), zdalne = emptySet())

        useCase.syncPurchasedCaps()

        coVerify(exactly = 0) { purchasedCapsService.scheduleReplaceAll(any(), any()) }
        verify(exactly = 0) { localStore.replaceAllLocally(any()) }
    }
}

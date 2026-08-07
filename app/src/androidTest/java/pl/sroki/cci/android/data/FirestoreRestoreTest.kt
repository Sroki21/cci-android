package pl.sroki.cci.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import pl.sroki.cci.android.data.model.CapSnapshot

/**
 * authManager i wszystkie trzy serwisy Firestore są mockk — ten plik kiedyś naprawdę logował się
 * do Firebase (FirebaseAuth.getInstance()/FirebaseFirestore.getInstance()) i zapisywał fałszywy
 * kapsel ("Test Cap"/Poland) do żywej kolekcji, bez sprzątania w tearDown(). Na urządzeniu
 * z aktywną sesją (telefon dewelopera) trafiałby wprost do produkcji. "Seed" jest teraz
 * stubowaniem fetchAll() zamiast realnego zapisu do Firestore i odczytu z powrotem.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreRestoreTest {

    private companion object {
        const val TEST_UID = "test-uid-firestore-restore"
    }

    private lateinit var db: CciDatabase
    private val binderFs = mockk<BinderFirestoreService>(relaxed = true)
    private val binderPageFs = mockk<BinderPageFirestoreService>(relaxed = true)
    private val capPositionFs = mockk<CapPositionFirestoreService>(relaxed = true)
    private lateinit var restoreUseCase: FirestoreRestoreUseCase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CciDatabase::class.java).build()
        val authManager = mockk<FirebaseAuthManager>()
        every { authManager.uid } returns MutableStateFlow(TEST_UID)
        restoreUseCase = FirestoreRestoreUseCase(
            context = context,
            authManager = authManager,
            database = db,
            binderDao = db.binderDao(),
            binderPageDao = db.binderPageDao(),
            capPositionDao = db.capPositionDao(),
            capCacheDao = db.capCacheDao(),
            binderService = binderFs,
            binderPageService = binderPageFs,
            capPositionService = capPositionFs,
            purchasedCapsService = mockk(relaxed = true),
            purchasedCapsLocalStore = mockk(relaxed = true)
        )
        // Odpowiednik dawnego seeda: Firestore "ma" jeden Binder -> jedną BinderPage -> jedną
        // CapPosition ze snapshotem, bez faktycznego zapisu do chmury.
        coEvery { binderFs.fetchAll(TEST_UID) } returns listOf(
            BinderDocument("fsB1", "Restore Test Klaser")
        )
        coEvery { binderPageFs.fetchAll(TEST_UID) } returns listOf(
            BinderPageDocument("fsP1", "fsB1", 1)
        )
        coEvery { capPositionFs.fetchAll(TEST_UID) } returns listOf(
            CapPositionDocument(
                "fsCap1", "fsP1", 3, 77L,
                CapSnapshot(
                    name = "Test Cap",
                    country = "Poland",
                    imageUrl = "https://example.test/caps/77.deadbeef.jpeg",
                    createdAt = "2011-04-30T15:20:54Z",
                    createdById = 2,
                    updatedAt = "2023-06-02T22:35:21Z"
                )
            )
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun restoreIfEmpty_rebuildsHierarchy() = runBlocking {
        restoreUseCase.restoreIfEmpty()

        val binders = db.binderDao().getAll().first()
        assertEquals(1, binders.size)
        assertEquals("Restore Test Klaser", binders[0].name)
        assertNotNull(binders[0].firestoreId)

        val pages = db.binderPageDao().getByBinderId(binders[0].id).first()
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertNotNull(pages[0].firestoreId)

        val positions = db.capPositionDao().getByPage(pages[0].id).first()
        assertEquals(1, positions.size)
        assertEquals(3, positions[0].position)
        assertEquals(77L, positions[0].capId)
        assertNotNull(positions[0].firestoreId)

        // Snapshot odtworzony do cap_cache (render offline po reinstalacji).
        val cache = db.capCacheDao().getByIds(listOf(77L))
        assertEquals(1, cache.size)
        assertEquals("Test Cap", cache[0].name)
        assertEquals("Poland", cache[0].country)
        assertEquals(2, cache[0].createdById)
    }

    @Test
    fun restoreIfEmpty_skipsWhenRoomNotEmpty() = runBlocking {
        // Preloaduj Room z innym rekordem
        db.binderDao().insert(Binder(name = "Istniejący"))
        restoreUseCase.restoreIfEmpty()
        // Dane z Firestore nie powinny być dopisane
        val binders = db.binderDao().getAll().first()
        assertEquals(1, binders.size)
        assertEquals("Istniejący", binders[0].name)
    }
}

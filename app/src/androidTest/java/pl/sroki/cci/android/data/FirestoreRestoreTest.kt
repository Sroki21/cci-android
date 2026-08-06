package pl.sroki.cci.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import io.mockk.mockk

@RunWith(AndroidJUnit4::class)
class FirestoreRestoreTest {

    private lateinit var db: CciDatabase
    private lateinit var authManager: FirebaseAuthManager
    private lateinit var binderFs: BinderFirestoreService
    private lateinit var binderPageFs: BinderPageFirestoreService
    private lateinit var capPositionFs: CapPositionFirestoreService
    private lateinit var restoreUseCase: FirestoreRestoreUseCase
    private lateinit var uid: String

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CciDatabase::class.java).build()
        val auth = FirebaseAuth.getInstance()
        authManager = FirebaseAuthManager(auth)
        authManager.ensureSignedIn()
        uid = authManager.uid.value ?: error("Brak uid po logowaniu")
        val firestore = FirebaseFirestore.getInstance()
        binderFs = BinderFirestoreService(firestore)
        binderPageFs = BinderPageFirestoreService(firestore)
        capPositionFs = CapPositionFirestoreService(firestore)
        restoreUseCase = FirestoreRestoreUseCase(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
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
        // Seed Firestore: 1 Binder → 1 BinderPage → 1 CapPosition (ze snapshotem)
        val binderFsId = binderFs.newDocumentId(uid)
        binderFs.scheduleCreate(uid, binderFsId, "Restore Test Klaser")
        val pageFsId = binderPageFs.newDocumentId(uid)
        binderPageFs.scheduleCreate(uid, pageFsId, binderFsId, 1)
        capPositionFs.scheduleCreate(
            uid, capPositionFs.newDocumentId(uid), pageFsId, 3, 77L,
            pl.sroki.cci.android.data.model.CapSnapshot(
                name = "Test Cap",
                country = "Poland",
                imageUrl = "https://example.test/caps/77.deadbeef.jpeg",
                createdAt = "2011-04-30T15:20:54Z",
                createdById = 2,
                updatedAt = "2023-06-02T22:35:21Z"
            )
        )
        // Krótkie oczekiwanie — operacje schedule są fire-and-forget, Firestore SDK buforuje lokalnie
        Thread.sleep(500)
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
        db.binderDao().insert(pl.sroki.cci.android.data.datasource.local.entity.Binder(name = "Istniejący"))
        restoreUseCase.restoreIfEmpty()
        // Dane z Firestore nie powinny być dopisane
        val binders = db.binderDao().getAll().first()
        assertEquals(1, binders.size)
        assertEquals("Istniejący", binders[0].name)
    }
}

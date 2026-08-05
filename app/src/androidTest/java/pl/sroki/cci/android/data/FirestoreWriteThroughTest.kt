package pl.sroki.cci.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService

@RunWith(AndroidJUnit4::class)
class FirestoreWriteThroughTest {

    private lateinit var db: CciDatabase
    private lateinit var binderRepository: BinderRepository
    private lateinit var binderPageRepository: BinderPageRepository
    private lateinit var capPositionRepository: CapPositionRepository

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CciDatabase::class.java).build()
        val auth = FirebaseAuth.getInstance()
        val authManager = FirebaseAuthManager(auth)
        authManager.ensureSignedIn()
        val firestore = FirebaseFirestore.getInstance()
        val binderFs = BinderFirestoreService(firestore)
        val binderPageFs = BinderPageFirestoreService(firestore)
        val capPositionFs = CapPositionFirestoreService(firestore)
        binderRepository = BinderRepository(
            binderDao = db.binderDao(),
            binderPageDao = db.binderPageDao(),
            capPositionDao = db.capPositionDao(),
            binderFirestoreService = binderFs,
            binderPageFirestoreService = binderPageFs,
            authManager = authManager
        )
        binderPageRepository = BinderPageRepository(
            db = db,
            dao = db.binderPageDao(),
            binderDao = db.binderDao(),
            capPositionDao = db.capPositionDao(),
            binderPageFirestoreService = binderPageFs,
            capPositionFirestoreService = capPositionFs,
            authManager = authManager
        )
        capPositionRepository = CapPositionRepository(
            dao = db.capPositionDao(),
            binderPageDao = db.binderPageDao(),
            capCacheDao = db.capCacheDao(),
            capPositionFirestoreService = capPositionFs,
            authManager = authManager
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun createBinder_persistsFirestoreId() = runTest {
        val binderId = binderRepository.create("Test Klaser")
        val binder = db.binderDao().getById(binderId)
        assertNotNull("firestoreId powinien być ustawiony po create()", binder?.firestoreId)
    }

    @Test
    fun addPage_persistsFirestoreId() = runTest {
        val binderId = binderRepository.create("Test Klaser")
        val pageId = binderPageRepository.addPage(binderId)
        val page = db.binderPageDao().getById(pageId)
        assertNotNull("firestoreId strony powinien być ustawiony po addPage()", page?.firestoreId)
    }

    @Test
    fun assignCapPosition_persistsFirestoreId() = runTest {
        val binderId = binderRepository.create("Test Klaser")
        val pageId = binderPageRepository.addPage(binderId)
        capPositionRepository.assign(binderPageId = pageId, position = 1, capId = 42L)
        val pos = db.capPositionDao().getByCapId(42L)
        assertNotNull("firestoreId pozycji powinien być ustawiony po assign()", pos?.firestoreId)
    }
}

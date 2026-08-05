package pl.sroki.cci.android.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService

@RunWith(AndroidJUnit4::class)
class FirestoreRestoreUseCaseTest {

    private companion object {
        const val TEST_UID = "test-uid-phase-c"
    }

    private lateinit var db: CciDatabase
    private val binderService = mockk<BinderFirestoreService>(relaxed = true)
    private val binderPageService = mockk<BinderPageFirestoreService>(relaxed = true)
    private val capPositionService = mockk<CapPositionFirestoreService>(relaxed = true)
    private val authManager = mockk<FirebaseAuthManager>()
    private lateinit var useCase: FirestoreRestoreUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { authManager.uid } returns MutableStateFlow(TEST_UID)
        useCase = FirestoreRestoreUseCase(
            context = ApplicationProvider.getApplicationContext<Context>(),
            authManager = authManager,
            database = db,
            binderDao = db.binderDao(),
            binderPageDao = db.binderPageDao(),
            capPositionDao = db.capPositionDao(),
            capCacheDao = db.capCacheDao(),
            binderService = binderService,
            binderPageService = binderPageService,
            capPositionService = capPositionService
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun restoreFromFirestore_partialFailure_rollsBack() = runBlocking {
        // Pre-condition: 1 existing binder in Room
        db.binderDao().insert(Binder(name = "pre-existing"))

        // Firestore returns 1 binder + 1 page + 1 cap so insertRestored() is reached
        coEvery { binderService.fetchAll(TEST_UID) } returns listOf(
            BinderDocument("fsB1", "Restored Klaser")
        )
        coEvery { binderPageService.fetchAll(TEST_UID) } returns listOf(
            BinderPageDocument("fsP1", "fsB1", 1)
        )
        coEvery { capPositionService.fetchAll(TEST_UID) } returns listOf(
            CapPositionDocument("fsCap1", "fsP1", 1, 42L)
        )

        // failingCapDao throws inside database.withTransaction {} → triggers rollback
        val failingCapDao = mockk<CapPositionDao>(relaxed = true)
        coEvery { failingCapDao.insertOrIgnore(any()) } throws SQLiteException("forced failure in test")
        val failingUseCase = FirestoreRestoreUseCase(
            ApplicationProvider.getApplicationContext<Context>(),
            authManager, db, db.binderDao(), db.binderPageDao(),
            failingCapDao, db.capCacheDao(),
            binderService, binderPageService, capPositionService
        )

        var threw = false
        try {
            failingUseCase.restoreFromFirestore()
        } catch (e: Exception) {
            threw = true
        }

        assertTrue("restoreFromFirestore powinno rzucić wyjątek gdy DAO zawiedzie", threw)
        assertEquals(
            "database.withTransaction rollback: pre-existing binder powinien wrócić",
            1, db.binderDao().countAll()
        )
    }

    @Test
    fun restoreFromFirestore_kolizjaSlotu_raportujePominietaPozycje() = runBlocking {
        // Dwa dokumenty Firestore celują w ten sam slot (strona 1, pozycja 5). Wcześniej drugi
        // ginął bezgłośnie przez insertOrIgnore — tak wypadły z kolekcji cztery realne kapsle.
        coEvery { binderService.fetchAll(TEST_UID) } returns listOf(
            BinderDocument("fsB1", "Europa 6")
        )
        coEvery { binderPageService.fetchAll(TEST_UID) } returns listOf(
            BinderPageDocument("fsP1", "fsB1", 1)
        )
        coEvery { capPositionService.fetchAll(TEST_UID) } returns listOf(
            CapPositionDocument("fsCap1", "fsP1", 5, 21668L),
            CapPositionDocument("fsCap2", "fsP1", 5, 86226L)
        )

        val result = useCase.restoreFromFirestore()

        assertTrue("oczekiwano Success", result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals("dokładnie jedna pozycja powinna zostać pominięta", 1, success.skipped.size)
        val skipped = success.skipped.first()
        assertEquals("pominięty kapsel", 86226L, skipped.capId)
        assertEquals("pozycja spornego slotu", 5, skipped.position)
        assertEquals("kapsel, który zajął slot", 21668L, skipped.occupiedByCapId)
    }

    @Test
    fun restoreFromFirestore_bezKolizji_niczegoNieRaportuje() = runBlocking {
        coEvery { binderService.fetchAll(TEST_UID) } returns listOf(
            BinderDocument("fsB1", "Europa 6")
        )
        coEvery { binderPageService.fetchAll(TEST_UID) } returns listOf(
            BinderPageDocument("fsP1", "fsB1", 1)
        )
        coEvery { capPositionService.fetchAll(TEST_UID) } returns listOf(
            CapPositionDocument("fsCap1", "fsP1", 5, 21668L),
            CapPositionDocument("fsCap2", "fsP1", 6, 86226L)
        )

        val result = useCase.restoreFromFirestore() as RestoreResult.Success

        assertTrue("brak kolizji — lista pominiętych pusta", result.skipped.isEmpty())
        assertEquals("obie pozycje wstawione", 2, db.capPositionDao().countAll())
    }

    @Test
    fun restoreIfEmpty_concurrentCalls_noDuplicates() = runBlocking {
        // runBlocking używa jednowątkowego BlockingEventLoop — interleaving zachodzi przez
        // kooperatywne zawieszenie na delay(50) wewnątrz withLock. job1 zawiesza się trzymając
        // Mutex; job2 dostaje szansę sprawdzić countAll(). NIE zamieniaj na runTest: jego
        // wirtualny czas i scheduler nie replikują tego interleavingu i test przestanie
        // rozróżniać kod z Mutex od kodu bez Mutex.
        coEvery { binderService.fetchAll(TEST_UID) } coAnswers {
            delay(50)
            listOf(BinderDocument("fsB1", "Test Klaser"))
        }
        coEvery { binderPageService.fetchAll(TEST_UID) } returns emptyList()
        coEvery { capPositionService.fetchAll(TEST_UID) } returns emptyList()

        val job1 = launch { useCase.restoreIfEmpty() }
        val job2 = launch { useCase.restoreIfEmpty() }
        joinAll(job1, job2)

        assertEquals(
            "Mutex w restoreIfEmpty: drugie wywołanie widzi countAll>0 i wraca wcześniej",
            1, db.binderDao().countAll()
        )
    }

    @Test
    fun restoreIfEmpty_and_restoreFromFirestore_concurrent_noDuplicates() = runBlocking {
        // runBlocking: jednowątkowy BlockingEventLoop, interleaving przez kooperatywne zawieszenie.
        // NIE zamieniaj na runTest — scheduler wirtualnego czasu nie replikuje tego interleavingu.
        coEvery { binderService.fetchAll(TEST_UID) } coAnswers {
            delay(50)
            listOf(BinderDocument("fsB1", "Test Klaser"))
        }
        coEvery { binderPageService.fetchAll(TEST_UID) } returns emptyList()
        coEvery { capPositionService.fetchAll(TEST_UID) } returns emptyList()

        val job1 = launch { useCase.restoreIfEmpty() }
        val job2 = launch { useCase.restoreFromFirestore() }
        joinAll(job1, job2)

        assertEquals(
            "Mutex serializuje restoreIfEmpty i restoreFromFirestore — brak duplikatów",
            1, db.binderDao().countAll()
        )
    }
}

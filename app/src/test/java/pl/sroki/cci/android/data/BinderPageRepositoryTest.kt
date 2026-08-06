package pl.sroki.cci.android.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService

/**
 * Numer nowej strony brał się z COUNT(*) + 1, a UNIQUE stoi na (binder_id, page_number). Klaser
 * ze stronami 1, 2, 3, z którego usunięto stronę 2, ma COUNT = 2 i numery 1, 3 — dodanie strony
 * celowało w numer 3 i leciało na ograniczenie. Reprodukowalne palcami, a w dodatku dokument
 * strony szedł wtedy do Firestore mimo nieudanego zapisu lokalnego.
 */
class BinderPageRepositoryTest {

    private companion object {
        const val BINDER_ID = 5L
        const val UID = "test-uid"
    }

    private lateinit var dao: BinderPageDao
    private lateinit var binderDao: BinderDao
    private lateinit var firestore: BinderPageFirestoreService
    private lateinit var repo: BinderPageRepository

    @Before
    fun setUp() {
        val db = mockk<CciDatabase>()
        mockkStatic("androidx.room.RoomDatabaseKt")
        // withTransaction to extension, więc pierwszym argumentem jest receiver (baza),
        // a blok transakcji stoi na drugim miejscu.
        coEvery { db.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            secondArg<suspend () -> Any?>().invoke()
        }

        dao = mockk(relaxed = true)
        binderDao = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        val authManager = mockk<FirebaseAuthManager>()
        coEvery { authManager.uid } returns MutableStateFlow(UID)
        coEvery { binderDao.getById(BINDER_ID) } returns Binder(id = BINDER_ID, name = "Belgia", firestoreId = "binder-fs-1")
        coEvery { firestore.newDocumentId(UID) } returns "page-fs-1"

        repo = BinderPageRepository(
            db = db,
            dao = dao,
            binderDao = binderDao,
            capPositionDao = mockk(relaxed = true),
            binderPageFirestoreService = firestore,
            capPositionFirestoreService = mockk(relaxed = true),
            authManager = authManager
        )
    }

    @Test
    fun `nowa strona dostaje numer o jeden wyzszy od najwyzszego zajetego`() = runTest {
        // Klaser po usunięciu strony 2: numery 1 i 3, czyli COUNT = 2, MAX = 3.
        coEvery { dao.countByBinderId(BINDER_ID) } returns 2
        coEvery { dao.maxPageNumber(BINDER_ID) } returns 3
        val wstawiona = slot<BinderPage>()
        coEvery { dao.insert(capture(wstawiona)) } returns 99L

        repo.addPage(BINDER_ID)

        assertEquals(4, wstawiona.captured.pageNumber)
    }

    @Test
    fun `pierwsza strona w pustym klaserze dostaje numer 1`() = runTest {
        coEvery { dao.countByBinderId(BINDER_ID) } returns 0
        coEvery { dao.maxPageNumber(BINDER_ID) } returns 0
        val wstawiona = slot<BinderPage>()
        coEvery { dao.insert(capture(wstawiona)) } returns 1L

        repo.addPage(BINDER_ID)

        assertEquals(1, wstawiona.captured.pageNumber)
    }

    @Test
    fun `limit 15 stron liczy strony, nie numery`() = runTest {
        // Numeracja sięga 20, ale stron jest 14 — dodanie musi przejść.
        coEvery { dao.countByBinderId(BINDER_ID) } returns 14
        coEvery { dao.maxPageNumber(BINDER_ID) } returns 20
        val wstawiona = slot<BinderPage>()
        coEvery { dao.insert(capture(wstawiona)) } returns 42L

        repo.addPage(BINDER_ID)

        assertEquals(21, wstawiona.captured.pageNumber)
    }

    /**
     * E2: scheduleCreate stało wewnątrz db.withTransaction. Przerwana transakcja cofała Rooma,
     * ale dokumentu strony w chmurze nikt już nie wycofywał — wracał przy najbliższym restore.
     */
    @Test
    fun `pelny klaser nie zostawia dokumentu strony w chmurze`() = runTest {
        coEvery { dao.countByBinderId(BINDER_ID) } returns 15
        coEvery { dao.maxPageNumber(BINDER_ID) } returns 15

        val wynik = runCatching { repo.addPage(BINDER_ID) }

        assertTrue(wynik.isFailure)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { firestore.scheduleCreate(any(), any(), any(), any()) }
    }

    @Test
    fun `nieudany zapis lokalny nie wysyla strony do chmury`() = runTest {
        coEvery { dao.countByBinderId(BINDER_ID) } returns 2
        coEvery { dao.maxPageNumber(BINDER_ID) } returns 3
        coEvery { dao.insert(any()) } throws mockk<SQLiteConstraintException>(relaxed = true)

        val wynik = runCatching { repo.addPage(BINDER_ID) }

        assertTrue(wynik.isFailure)
        coVerify(exactly = 0) { firestore.scheduleCreate(any(), any(), any(), any()) }
    }

    @Test
    fun `chmura dostaje strone dopiero po zapisie lokalnym`() = runTest {
        coEvery { dao.countByBinderId(BINDER_ID) } returns 2
        coEvery { dao.maxPageNumber(BINDER_ID) } returns 3
        coEvery { dao.insert(any()) } returns 99L

        repo.addPage(BINDER_ID)

        coVerifyOrder {
            dao.insert(any())
            firestore.scheduleCreate(UID, "page-fs-1", "binder-fs-1", 4)
        }
    }
}

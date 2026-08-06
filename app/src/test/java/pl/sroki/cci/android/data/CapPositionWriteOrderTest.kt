package pl.sroki.cci.android.data

import android.database.sqlite.SQLiteConstraintException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService

/**
 * Zapis do Firestore szedł PRZED zapisem do Roomu. Gdy insert padał na UNIQUE(strona, pozycja)
 * — czyli w scenariuszu, który UI pokazuje jako „Pozycja jest już zajęta" — dokument pozycji
 * został już wysłany do chmury: pozycja istniała w Firestore i nie istniała lokalnie, a przy
 * najbliższym odtwarzaniu wracała i potrafiła wyprzeć ze slotu inny kapsel. W reassign było
 * gorzej: kasowanie starego dokumentu i utworzenie nowego szło przed Roomem, więc nieudane
 * przeniesienie zostawiało chmurę przestawioną, a Rooma na starej pozycji.
 */
class CapPositionWriteOrderTest {

    private companion object {
        const val UID = "test-uid"
        const val PAGE_ID = 3L
        const val PAGE_FS_ID = "page-fs-1"
        const val NEW_DOC_ID = "pos-fs-nowy"
        const val CAP_ID = 165216L
    }

    private lateinit var dao: CapPositionDao
    private lateinit var firestore: CapPositionFirestoreService
    private lateinit var purchased: PurchasedCapsLocalStore
    private lateinit var repo: CapPositionRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        firestore = mockk(relaxed = true)
        purchased = mockk(relaxed = true)
        val binderPageDao = mockk<BinderPageDao>(relaxed = true)
        val capCacheDao = mockk<CapCacheDao>(relaxed = true)
        val authManager = mockk<FirebaseAuthManager>()

        coEvery { authManager.uid } returns MutableStateFlow(UID)
        coEvery { binderPageDao.getById(PAGE_ID) } returns
            BinderPage(id = PAGE_ID, binderId = 1L, pageNumber = 1, firestoreId = PAGE_FS_ID)
        coEvery { capCacheDao.getByIds(any()) } returns emptyList()
        coEvery { firestore.newDocumentId(UID) } returns NEW_DOC_ID

        repo = CapPositionRepository(
            dao = dao,
            binderPageDao = binderPageDao,
            capCacheDao = capCacheDao,
            capPositionFirestoreService = firestore,
            purchasedCapsLocalStore = purchased,
            authManager = authManager
        )
    }

    @Test
    fun `zajeta pozycja nie zostawia dokumentu w chmurze`() = runTest {
        coEvery { dao.insert(any()) } throws mockk<SQLiteConstraintException>(relaxed = true)

        val wynik = runCatching { repo.assign(PAGE_ID, position = 4, capId = CAP_ID) }

        assertTrue(wynik.isFailure)
        coVerify(exactly = 0) { firestore.scheduleCreate(any(), any(), any(), any(), any(), any(), any()) }
        // Kapsel nie trafił do klasera, więc nie ma powodu zdejmować go z listy zakupionych.
        coVerify(exactly = 0) { purchased.remove(any()) }
    }

    @Test
    fun `chmura dostaje pozycje dopiero po zapisie lokalnym`() = runTest {
        coEvery { dao.insert(any()) } returns 12L

        repo.assign(PAGE_ID, position = 4, capId = CAP_ID)

        coVerifyOrder {
            dao.insert(any())
            firestore.scheduleCreate(UID, NEW_DOC_ID, PAGE_FS_ID, 4, CAP_ID, any(), any())
        }
    }

    @Test
    fun `nieudane przeniesienie nie rusza chmury`() = runTest {
        coEvery { dao.getByCapId(CAP_ID) } returns
            CapPosition(id = 1L, binderPageId = 9L, position = 2, capId = CAP_ID, firestoreId = "pos-fs-stary")
        coEvery { dao.reassignFull(any(), any()) } throws mockk<SQLiteConstraintException>(relaxed = true)

        val wynik = runCatching { repo.reassign(CAP_ID, newBinderPageId = PAGE_ID, newPosition = 4) }

        assertTrue(wynik.isFailure)
        coVerify(exactly = 0) { firestore.scheduleDelete(any(), any()) }
        coVerify(exactly = 0) { firestore.scheduleCreate(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `udane przeniesienie kasuje stary dokument i tworzy nowy — po Roomie`() = runTest {
        coEvery { dao.getByCapId(CAP_ID) } returns
            CapPosition(id = 1L, binderPageId = 9L, position = 2, capId = CAP_ID, firestoreId = "pos-fs-stary")

        repo.reassign(CAP_ID, newBinderPageId = PAGE_ID, newPosition = 4)

        coVerifyOrder {
            dao.reassignFull(CAP_ID, any())
            firestore.scheduleDelete(UID, "pos-fs-stary")
            firestore.scheduleCreate(UID, NEW_DOC_ID, PAGE_FS_ID, 4, CAP_ID, any(), any())
        }
    }
}

package pl.sroki.cci.android.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import pl.sroki.cci.android.model.binder.BinderPageView

@Singleton
class BinderPageRepository @Inject constructor(
    private val db: CciDatabase,
    private val dao: BinderPageDao,
    private val binderDao: BinderDao,
    private val capPositionDao: CapPositionDao,
    private val binderPageFirestoreService: BinderPageFirestoreService,
    private val capPositionFirestoreService: CapPositionFirestoreService,
    private val authManager: FirebaseAuthManager
) {
    fun getByBinder(binderId: Long): Flow<List<BinderPageView>> =
        dao.getByBinderId(binderId).map { list -> list.map { it.toView() } }

    suspend fun addPage(binderId: Long): Long {
        val uid = authManager.uid.value
        return db.withTransaction {
            val count = dao.countByBinderId(binderId)
            check(count < 15) { "Klaser może mieć maksymalnie 15 stron" }
            // Numer z MAX, nie z COUNT — po usunięciu strony ze środka liczba stron przestaje
            // odpowiadać najwyższemu numerowi i COUNT+1 trafiał w numer już zajęty.
            val pageNumber = dao.maxPageNumber(binderId) + 1
            val firestoreId = if (uid != null) {
                val binder = binderDao.getById(binderId)
                binder?.firestoreId?.let { binderFirestoreId ->
                    binderPageFirestoreService.scheduleCreate(uid, binderFirestoreId, pageNumber)
                }
            } else null
            try {
                dao.insert(BinderPage(binderId = binderId, pageNumber = pageNumber, firestoreId = firestoreId))
            } catch (e: SQLiteConstraintException) {
                // Nie powinno się zdarzyć po przejściu na MAX+1, ale do UI musi iść zdanie,
                // a nie surowy wyjątek SQLite — tak jak w updatePageNumber i moveToBinder.
                throw IllegalStateException("Strona o numerze $pageNumber już istnieje w tym klaserze")
            }
        }
    }

    suspend fun updatePageNumber(pageId: Long, newPageNumber: Int) {
        require(newPageNumber >= 1) { "Numer strony musi być większy od zera" }
        try {
            dao.updatePageNumber(pageId, newPageNumber)
        } catch (e: SQLiteConstraintException) {
            throw IllegalStateException("Strona o numerze $newPageNumber już istnieje w tym klaserze")
        }
        val uid = authManager.uid.value
        if (uid != null) {
            val page = dao.getById(pageId)
            page?.firestoreId?.let { binderPageFirestoreService.scheduleUpdate(uid, it, newPageNumber) }
        }
    }

    suspend fun moveToBinder(pageId: Long, newBinderId: Long) {
        val page = dao.getById(pageId) ?: return
        if (page.binderId == newBinderId) return
        check(dao.countByBinderId(newBinderId) < 15) { "Docelowy klaser może mieć maksymalnie 15 stron" }
        try {
            dao.updateBinderId(pageId, newBinderId)
        } catch (e: SQLiteConstraintException) {
            throw IllegalStateException(
                "Strona o numerze ${page.pageNumber} już istnieje w docelowym klaserze — zmień najpierw numer strony"
            )
        }
        val uid = authManager.uid.value
        if (uid != null) {
            val targetFirestoreId = binderDao.getById(newBinderId)?.firestoreId
            if (page.firestoreId != null && targetFirestoreId != null) {
                binderPageFirestoreService.scheduleMove(uid, page.firestoreId, targetFirestoreId)
            }
        }
    }

    suspend fun deletePage(pageId: Long) {
        val uid = authManager.uid.value
        if (uid != null) {
            val page = dao.getById(pageId)
            val positions = capPositionDao.getByPage(pageId).first()
            positions.forEach { pos ->
                pos.firestoreId?.let { capPositionFirestoreService.scheduleDelete(uid, it) }
            }
            page?.firestoreId?.let { binderPageFirestoreService.scheduleDelete(uid, it) }
        }
        dao.deleteById(pageId)
    }
}

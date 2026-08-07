package pl.sroki.cci.android.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import io.sentry.Sentry
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
        val binderFirestoreId = if (uid != null) binderDao.getById(binderId)?.firestoreId else null
        // Id dokumentu nadaje Firestore lokalnie, bez zapisu — wysyłka czeka na powodzenie
        // transakcji. Wcześniej scheduleCreate stało w środku db.withTransaction: przerwana
        // transakcja (limit 15 stron albo kolizja numeru) cofała Rooma, ale dokument strony
        // zostawał w chmurze i wracał przy najbliższym odtwarzaniu.
        val firestoreId = if (uid != null && binderFirestoreId != null) {
            binderPageFirestoreService.newDocumentId(uid)
        } else null

        val strona = db.withTransaction {
            val count = dao.countByBinderId(binderId)
            check(count < 15) { "Klaser może mieć maksymalnie 15 stron" }
            // Numer z MAX, nie z COUNT — po usunięciu strony ze środka liczba stron przestaje
            // odpowiadać najwyższemu numerowi i COUNT+1 trafiał w numer już zajęty.
            val pageNumber = dao.maxPageNumber(binderId) + 1
            val id = try {
                dao.insert(BinderPage(binderId = binderId, pageNumber = pageNumber, firestoreId = firestoreId))
            } catch (e: SQLiteConstraintException) {
                // Nie powinno się zdarzyć po przejściu na MAX+1, ale do UI musi iść zdanie,
                // a nie surowy wyjątek SQLite — tak jak w updatePageNumber i moveToBinder.
                throw IllegalStateException("Strona o numerze $pageNumber już istnieje w tym klaserze")
            }
            id to pageNumber
        }

        if (uid != null && binderFirestoreId != null && firestoreId != null) {
            binderPageFirestoreService.scheduleCreate(uid, firestoreId, binderFirestoreId, strona.second)
        }
        return strona.first
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
        // Sprawdzenie limitu i zapis w jednej transakcji — inaczej równoległe odtwarzanie
        // z chmury mogło dołożyć stronę między jednym a drugim (TOCTOU).
        db.withTransaction {
            check(dao.countByBinderId(newBinderId) < 15) { "Docelowy klaser może mieć maksymalnie 15 stron" }
            try {
                dao.updateBinderId(pageId, newBinderId)
            } catch (e: SQLiteConstraintException) {
                throw IllegalStateException(
                    "Strona o numerze ${page.pageNumber} już istnieje w docelowym klaserze — zmień najpierw numer strony"
                )
            }
        }
        val uid = authManager.uid.value
        if (uid != null) {
            val targetFirestoreId = binderDao.getById(newBinderId)?.firestoreId
            if (page.firestoreId != null && targetFirestoreId != null) {
                binderPageFirestoreService.scheduleMove(uid, page.firestoreId, targetFirestoreId)
            } else {
                // Strona albo klaser powstały offline (brak firestoreId): lokalnie przeniesienie
                // się udaje, a chmura dalej wiąże stronę ze starym klaserem — po odtworzeniu
                // wróciłaby w złe miejsce. Cicho przejść obok tego nie wolno.
                val opis = "przeniesienie strony $pageId poza synchronizacją " +
                    "(page.firestoreId=${page.firestoreId}, docelowy=$targetFirestoreId)"
                Log.w("CCI_SYNC", opis)
                Sentry.captureMessage(opis)
            }
        }
    }

    suspend fun deletePage(pageId: Long) {
        val uid = authManager.uid.value
        val page = if (uid != null) dao.getById(pageId) else null
        val positions = if (uid != null) capPositionDao.getByPage(pageId).first() else emptyList()

        // Chmura dopiero po udanym usunięciu lokalnym — tak jak w BinderRepository.delete()
        // i addPage(). Wcześniej kolejność była odwrotna: nieudane lokalne usunięcie po udanym
        // skasowaniu w Firestore zostawiało dokumenty żywe lokalnie, ale skasowane w chmurze.
        dao.deleteById(pageId)

        if (uid != null) {
            positions.forEach { pos ->
                pos.firestoreId?.let { capPositionFirestoreService.scheduleDelete(uid, it) }
            }
            page?.firestoreId?.let { binderPageFirestoreService.scheduleDelete(uid, it) }
        }
    }
}

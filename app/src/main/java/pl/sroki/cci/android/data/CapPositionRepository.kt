package pl.sroki.cci.android.data

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.data.model.CapSnapshot
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.ProducerSelection
import javax.inject.Inject
import javax.inject.Singleton
import pl.sroki.cci.android.model.binder.CapSlot
import pl.sroki.cci.android.model.binder.POSITIONS_PER_PAGE

@Singleton
class CapPositionRepository @Inject constructor(
    private val dao: CapPositionDao,
    private val binderPageDao: BinderPageDao,
    private val capCacheDao: CapCacheDao,
    private val capPositionFirestoreService: CapPositionFirestoreService,
    private val purchasedCapsLocalStore: PurchasedCapsLocalStore,
    private val authManager: FirebaseAuthManager
) {
    // Wybór producenta zapisany lokalnie musi trafić do każdego nowo tworzonego dokumentu
    // pozycji — inaczej kapsel wybrany PRZED przypięciem do klasera nadal gubiłby wybór.
    private suspend fun producerSelectionOf(capId: Long): ProducerSelection? =
        capCacheDao.getByIds(listOf(capId)).firstOrNull()?.let { cached ->
            cached.selectedProducerId?.let {
                ProducerSelection(it, cached.producer, cached.country)
            }
        }

    fun getByPage(binderPageId: Long): Flow<List<CapSlot>> =
        dao.getByPage(binderPageId).map { list -> list.map { it.toSlot() } }

    suspend fun getByCapId(capId: Long): CapPosition? = dao.getByCapId(capId)

    suspend fun getBinderInfoByCapId(capId: Long): CapBinderInfo? = dao.getBinderInfoByCapId(capId)

    suspend fun getAllCapIds(): List<Long> = dao.getAllCapIds()

    fun getAllCapIdsFlow(): Flow<Set<Long>> = dao.getAllCapIdsFlow().map { it.toSet() }

    suspend fun assign(binderPageId: Long, position: Int, capId: Long, snapshot: CapSnapshot? = null): Long {
        require(position in 1..POSITIONS_PER_PAGE) { "Pozycja musi być w zakresie 1-$POSITIONS_PER_PAGE" }
        val uid = authManager.uid.value
        val firestoreId = if (uid != null) {
            val page = binderPageDao.getById(binderPageId)
            page?.firestoreId?.let {
                capPositionFirestoreService.scheduleCreate(
                    uid, it, position, capId, snapshot, producerSelectionOf(capId)
                )
            }
        } else null
        try {
            val rowId = dao.insert(CapPosition(binderPageId = binderPageId, position = position, capId = capId, firestoreId = firestoreId))
            // Kapsel w klaserze nie jest już "zakupiony, ale nieprzypięty". Bez tego zostawał
            // na liście na zawsze — zakładka i tak go odfiltrowywała, ale zbiór puchł
            // o wpisy, które nigdy niczego nie pokażą, i szedł w tej postaci do Firestore.
            purchasedCapsLocalStore.remove(capId)
            return rowId
        } catch (e: SQLiteConstraintException) {
            throw IllegalStateException("Pozycja $position jest już zajęta na tej stronie")
        }
    }

    suspend fun reassign(capId: Long, newBinderPageId: Long, newPosition: Int, snapshot: CapSnapshot? = null) {
        require(newPosition in 1..POSITIONS_PER_PAGE) { "Pozycja musi być w zakresie 1-$POSITIONS_PER_PAGE" }
        val uid = authManager.uid.value
        val oldPos = dao.getByCapId(capId)
        val newFirestoreId = if (uid != null) {
            oldPos?.firestoreId?.let { capPositionFirestoreService.scheduleDelete(uid, it) }
            val newPage = binderPageDao.getById(newBinderPageId)
            newPage?.firestoreId?.let {
                capPositionFirestoreService.scheduleCreate(
                    uid, it, newPosition, capId, snapshot, producerSelectionOf(capId)
                )
            }
        } else null
        try {
            dao.reassignFull(capId, CapPosition(binderPageId = newBinderPageId, position = newPosition, capId = capId, firestoreId = newFirestoreId))
            purchasedCapsLocalStore.remove(capId)
        } catch (e: SQLiteConstraintException) {
            throw IllegalStateException("Pozycja $newPosition jest już zajęta na tej stronie")
        }
    }

    /** Odśwież snapshot pozycji w Firestore (po „zaakceptuj nowy" w rozjeździe). */
    suspend fun updateSnapshot(capId: Long, snapshot: CapSnapshot) {
        val uid = authManager.uid.value ?: return
        val fsId = dao.getByCapId(capId)?.firestoreId ?: return
        capPositionFirestoreService.scheduleUpdateSnapshot(uid, fsId, snapshot)
    }

    /**
     * Utrwal ręczny wybór producenta w Firestore, żeby przetrwał reinstalację aplikacji.
     * Kapsel bez pozycji w klaserze nie ma dokumentu do aktualizacji — wybór dojedzie
     * do chmury przy przypięciu, przez producerSelectionOf() w assign()/reassign().
     */
    suspend fun updateProducerSelection(capId: Long, selection: ProducerSelection) {
        val uid = authManager.uid.value ?: return
        val fsId = dao.getByCapId(capId)?.firestoreId ?: return
        capPositionFirestoreService.scheduleUpdateProducer(uid, fsId, selection)
    }

    suspend fun getTotalCount(): Int = dao.countAll()

    suspend fun unassign(capId: Long) {
        val uid = authManager.uid.value
        if (uid != null) {
            val pos = dao.getByCapId(capId)
            pos?.firestoreId?.let { capPositionFirestoreService.scheduleDelete(uid, it) }
        }
        dao.deleteByCapId(capId)
    }

    /**
     * Odpięcie kapsla, który zostaje w kolekcji — wraca na listę „zakupione, ale nieprzypięte".
     *
     * `assign`/`reassign` zdejmują kapsel z tej listy, a samo `unassign` go tam nie odkłada.
     * Odpięcie bez tego kroku gubiło kapsel: nie miał już pozycji w klaserze i nie było go
     * w magazynie zakupionych, więc znikał z aplikacji, mimo że po stronie API dalej był
     * w kolekcji. Dotyczy odpięcia z rozjazdu — przy przejściu na „Brak" kapsel wychodzi
     * z kolekcji i wtedy używa się zwykłego `unassign`.
     */
    suspend fun unassignToPurchased(capId: Long) {
        unassign(capId)
        purchasedCapsLocalStore.add(capId)
    }
}

package pl.sroki.cci.android.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import kotlinx.coroutines.flow.first
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import pl.sroki.cci.android.model.binder.BinderView

@Singleton
class BinderRepository @Inject constructor(
    private val db: CciDatabase,
    private val binderDao: BinderDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionDao: CapPositionDao,
    private val binderFirestoreService: BinderFirestoreService,
    private val binderPageFirestoreService: BinderPageFirestoreService,
    private val authManager: FirebaseAuthManager
) {
    fun getAll(): Flow<List<BinderView>> = binderDao.getAll().map { list -> list.map { it.toView() } }

    suspend fun create(name: String): Long {
        require(name.isNotBlank()) { "Nazwa klasera nie może być pusta" }
        val uid = authManager.uid.value
        // Id dokumentu powstaje lokalnie, więc trafia do Roomu przed jakąkolwiek wysyłką.
        val firestoreId = uid?.let { binderFirestoreService.newDocumentId(it) }
        val id = binderDao.insert(Binder(name = name, firestoreId = firestoreId))
        // Chmura dopiero po udanym zapisie lokalnym — inaczej nieudany insert zostawiał klaser
        // w Firestore, a ten wracał przy najbliższym odtwarzaniu.
        if (uid != null && firestoreId != null) binderFirestoreService.scheduleCreate(uid, firestoreId, name)
        return id
    }

    suspend fun delete(binderId: Long) {
        val uid = authManager.uid.value
        val binder = if (uid != null) binderDao.getById(binderId) else null
        val pages = if (uid != null) binderPageDao.getByBinderId(binderId).first() else emptyList()

        // Warunek „pusty klaser" i usunięcie w jednej transakcji — sprawdzany osobno mógł się
        // zdezaktualizować, zanim doszło do DELETE (kaskada FK zabrałaby wtedy kapsle).
        db.withTransaction {
            check(capPositionDao.countByBinderId(binderId) == 0) {
                "Klaser zawiera kapsle i nie może być usunięty"
            }
            binderDao.deleteById(binderId)
        }

        // Chmura dopiero po udanym usunięciu lokalnym: przerwany check nie może zostawić
        // skasowanych dokumentów klasera, którego nadal widać na liście.
        if (uid != null) {
            pages.forEach { page ->
                page.firestoreId?.let { binderPageFirestoreService.scheduleDelete(uid, it) }
            }
            binder?.firestoreId?.let { binderFirestoreService.scheduleDelete(uid, it) }
        }
    }
}

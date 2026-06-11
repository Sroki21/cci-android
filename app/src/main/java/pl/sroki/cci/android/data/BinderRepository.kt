package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderRepository @Inject constructor(
    private val binderDao: BinderDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionDao: CapPositionDao,
    private val binderFirestoreService: BinderFirestoreService,
    private val binderPageFirestoreService: BinderPageFirestoreService,
    private val authManager: FirebaseAuthManager
) {
    fun getAll(): Flow<List<Binder>> = binderDao.getAll()

    suspend fun create(name: String): Long {
        require(name.isNotBlank()) { "Nazwa klasera nie może być pusta" }
        val uid = authManager.uid.value
        val firestoreId = uid?.let { binderFirestoreService.scheduleCreate(it, name) }
        return binderDao.insert(Binder(name = name, firestoreId = firestoreId))
    }

    suspend fun delete(binderId: Long) {
        val occupied = capPositionDao.countByBinderId(binderId)
        check(occupied == 0) { "Klaser zawiera kapsle i nie może być usunięty" }
        val uid = authManager.uid.value
        if (uid != null) {
            val binder = binderDao.getById(binderId)
            val pages = binderPageDao.getByBinderId(binderId).first()
            pages.forEach { page ->
                page.firestoreId?.let { binderPageFirestoreService.scheduleDelete(uid, it) }
            }
            binder?.firestoreId?.let { binderFirestoreService.scheduleDelete(uid, it) }
        }
        binderDao.deleteById(binderId)
    }
}

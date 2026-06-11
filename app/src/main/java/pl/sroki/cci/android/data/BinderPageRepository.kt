package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderPageRepository @Inject constructor(
    private val dao: BinderPageDao,
    private val binderDao: BinderDao,
    private val capPositionDao: CapPositionDao,
    private val binderPageFirestoreService: BinderPageFirestoreService,
    private val capPositionFirestoreService: CapPositionFirestoreService,
    private val authManager: FirebaseAuthManager
) {
    fun getByBinder(binderId: Long): Flow<List<BinderPage>> = dao.getByBinderId(binderId)

    suspend fun addPage(binderId: Long): Long {
        val count = dao.countByBinderId(binderId)
        check(count < 15) { "Klaser może mieć maksymalnie 15 stron" }
        val uid = authManager.uid.value
        val firestoreId = if (uid != null) {
            val binder = binderDao.getById(binderId)
            binder?.firestoreId?.let { binderFirestoreId ->
                binderPageFirestoreService.scheduleCreate(uid, binderFirestoreId, count + 1)
            }
        } else null
        return dao.insert(BinderPage(binderId = binderId, pageNumber = count + 1, firestoreId = firestoreId))
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

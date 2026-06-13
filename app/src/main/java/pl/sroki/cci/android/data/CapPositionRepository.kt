package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapPositionRepository @Inject constructor(
    private val dao: CapPositionDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionFirestoreService: CapPositionFirestoreService,
    private val authManager: FirebaseAuthManager
) {
    fun getByPage(binderPageId: Long): Flow<List<CapPosition>> = dao.getByPage(binderPageId)

    suspend fun getByCapId(capId: Long): CapPosition? = dao.getByCapId(capId)

    suspend fun getBinderInfoByCapId(capId: Long): CapBinderInfo? = dao.getBinderInfoByCapId(capId)

    suspend fun getAllCapIds(): List<Long> = dao.getAllCapIds()

    fun getAllCapIdsFlow(): Flow<Set<Long>> = dao.getAllCapIdsFlow().map { it.toSet() }

    suspend fun assign(binderPageId: Long, position: Int, capId: Long): Long {
        require(position in 1..35) { "Pozycja musi być w zakresie 1-35" }
        val uid = authManager.uid.value
        val firestoreId = if (uid != null) {
            val page = binderPageDao.getById(binderPageId)
            page?.firestoreId?.let { capPositionFirestoreService.scheduleCreate(uid, it, position, capId) }
        } else null
        return dao.insert(CapPosition(binderPageId = binderPageId, position = position, capId = capId, firestoreId = firestoreId))
    }

    suspend fun reassign(capId: Long, newBinderPageId: Long, newPosition: Int) {
        require(newPosition in 1..35) { "Pozycja musi być w zakresie 1-35" }
        val uid = authManager.uid.value
        val oldPos = dao.getByCapId(capId)
        val newFirestoreId = if (uid != null) {
            oldPos?.firestoreId?.let { capPositionFirestoreService.scheduleDelete(uid, it) }
            val newPage = binderPageDao.getById(newBinderPageId)
            newPage?.firestoreId?.let { capPositionFirestoreService.scheduleCreate(uid, it, newPosition, capId) }
        } else null
        dao.reassignFull(capId, CapPosition(binderPageId = newBinderPageId, position = newPosition, capId = capId, firestoreId = newFirestoreId))
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
}

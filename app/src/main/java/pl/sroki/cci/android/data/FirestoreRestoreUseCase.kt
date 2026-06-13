package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRestoreUseCase @Inject constructor(
    private val authManager: FirebaseAuthManager,
    private val binderDao: BinderDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionDao: CapPositionDao,
    private val binderService: BinderFirestoreService,
    private val binderPageService: BinderPageFirestoreService,
    private val capPositionService: CapPositionFirestoreService
) {
    suspend fun restoreIfEmpty() {
        val uid = authManager.uid.value ?: return
        if (binderDao.countAll() > 0) return
        val fsIdToRoomId = mutableMapOf<String, Long>()
        binderService.fetchAll(uid).forEach { doc ->
            val id = binderDao.insert(Binder(name = doc.name, firestoreId = doc.firestoreId))
            fsIdToRoomId[doc.firestoreId] = id
        }
        val pageIdToRoomId = mutableMapOf<String, Long>()
        binderPageService.fetchAll(uid).forEach { doc ->
            val parentRoomId = fsIdToRoomId[doc.binderFirestoreId] ?: return@forEach
            val id = binderPageDao.insert(
                BinderPage(binderId = parentRoomId, pageNumber = doc.pageNumber, firestoreId = doc.firestoreId)
            )
            pageIdToRoomId[doc.firestoreId] = id
        }
        capPositionService.fetchAll(uid).forEach { doc ->
            val parentRoomId = pageIdToRoomId[doc.binderPageFirestoreId] ?: return@forEach
            capPositionDao.insert(
                CapPosition(
                    binderPageId = parentRoomId,
                    position = doc.position,
                    capId = doc.capId,
                    firestoreId = doc.firestoreId
                )
            )
        }
    }
}

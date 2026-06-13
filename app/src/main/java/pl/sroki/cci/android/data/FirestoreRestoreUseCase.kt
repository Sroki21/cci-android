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
    suspend fun migrateFromUid(oldUid: String): Int {
        val newUid = authManager.uid.value ?: error("Nie jesteś zalogowany do Firebase")
        require(newUid != oldUid) { "Stary i nowy UID są identyczne" }

        // oldFsId → (nowy Room ID, nowy Firestore ID)
        val binderFsIdMap = mutableMapOf<String, Pair<Long, String>>()
        val pageFsIdMap   = mutableMapOf<String, Pair<Long, String>>()

        binderService.fetchAll(oldUid).forEach { doc ->
            val newFsId = binderService.scheduleCreate(newUid, doc.name)
            val roomId  = binderDao.insert(Binder(name = doc.name, firestoreId = newFsId))
            binderFsIdMap[doc.firestoreId] = roomId to newFsId
        }

        binderPageService.fetchAll(oldUid).forEach { doc ->
            val (parentRoomId, newBinderFsId) = binderFsIdMap[doc.binderFirestoreId] ?: return@forEach
            val newFsId = binderPageService.scheduleCreate(newUid, newBinderFsId, doc.pageNumber)
            val roomId  = binderPageDao.insert(BinderPage(binderId = parentRoomId, pageNumber = doc.pageNumber, firestoreId = newFsId))
            pageFsIdMap[doc.firestoreId] = roomId to newFsId
        }

        var count = 0
        capPositionService.fetchAll(oldUid).forEach { doc ->
            val (parentRoomId, newPageFsId) = pageFsIdMap[doc.binderPageFirestoreId] ?: return@forEach
            val newFsId = capPositionService.scheduleCreate(newUid, newPageFsId, doc.position, doc.capId)
            capPositionDao.insert(CapPosition(binderPageId = parentRoomId, position = doc.position, capId = doc.capId, firestoreId = newFsId))
            count++
        }

        return count
    }

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

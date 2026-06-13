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
    suspend fun deduplicateRoomData() {
        binderDao.deduplicateByName()
    }

    suspend fun restoreIfEmpty() {
        val uid = authManager.uid.value ?: return
        if (binderDao.countAll() > 0) return

        val allBinders = binderService.fetchAll(uid)
        val allPages = binderPageService.fetchAll(uid)
        val allCaps = capPositionService.fetchAll(uid)

        // Deduplikacja po nazwie: na nazwę zostaje kopia z największą liczbą kapsli
        // (najpełniejsza). Nie polegamy na kolejności z Firestore, bo migracje mogły
        // zostawić chude duplikaty — wybranie złego osierociłoby strony i kapsle.
        val capsByPageFsId = allCaps.groupingBy { it.binderPageFirestoreId }.eachCount()
        val pagesByBinderFsId = allPages.groupBy { it.binderFirestoreId }
        fun capCount(binderFsId: String): Int =
            pagesByBinderFsId[binderFsId].orEmpty().sumOf { capsByPageFsId[it.firestoreId] ?: 0 }

        val chosenBinders = allBinders
            .groupBy { it.name }
            .values
            .map { dups -> dups.maxByOrNull { capCount(it.firestoreId) }!! }

        val fsIdToRoomId = mutableMapOf<String, Long>()
        chosenBinders.forEach { doc ->
            val id = binderDao.insert(Binder(name = doc.name, firestoreId = doc.firestoreId))
            fsIdToRoomId[doc.firestoreId] = id
        }
        val pageIdToRoomId = mutableMapOf<String, Long>()
        allPages.forEach { doc ->
            val parentRoomId = fsIdToRoomId[doc.binderFirestoreId] ?: return@forEach
            val id = binderPageDao.insert(
                BinderPage(binderId = parentRoomId, pageNumber = doc.pageNumber, firestoreId = doc.firestoreId)
            )
            pageIdToRoomId[doc.firestoreId] = id
        }
        allCaps.forEach { doc ->
            val parentRoomId = pageIdToRoomId[doc.binderPageFirestoreId] ?: return@forEach
            capPositionDao.insertOrIgnore(
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

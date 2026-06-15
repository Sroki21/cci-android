package pl.sroki.cci.android.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RestoreResult {
    data class Success(val binders: Int, val pages: Int, val caps: Int) : RestoreResult
    data object NotLoggedIn : RestoreResult
    data object Empty : RestoreResult
}

@Singleton
class FirestoreRestoreUseCase @Inject constructor(
    private val authManager: FirebaseAuthManager,
    private val database: CciDatabase,
    private val binderDao: BinderDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionDao: CapPositionDao,
    private val capCacheDao: CapCacheDao,
    private val binderService: BinderFirestoreService,
    private val binderPageService: BinderPageFirestoreService,
    private val capPositionService: CapPositionFirestoreService
) {
    private val restoreIfEmptyMutex = Mutex()

    suspend fun deduplicateRoomData() {
        binderDao.deduplicateByName()
    }

    suspend fun restoreIfEmpty() {
        val uid = authManager.uid.value ?: return
        restoreIfEmptyMutex.withLock {
            if (binderDao.countAll() > 0) return
            val allBinders = binderService.fetchAll(uid)
            val allPages = binderPageService.fetchAll(uid)
            val allCaps = capPositionService.fetchAll(uid)
            insertRestored(chooseBinders(allBinders, allPages, allCaps), allPages, allCaps)
        }
    }

    /**
     * Wymuszone ponowne pobranie z Firestore — także gdy lokalna baza nie jest pusta.
     * Bezpieczeństwo: najpierw pobiera komplet z Firestore i tylko gdy się uda oraz nie
     * jest pusty, atomowo (transakcja) czyści lokalne klasery i wstawia świeże dane.
     * Nie kasuje cache krajów/zdjęć (cap_cache) — jest niezależny od umiejscowienia.
     */
    suspend fun restoreFromFirestore(): RestoreResult {
        val uid = authManager.uid.value ?: return RestoreResult.NotLoggedIn

        val allBinders = binderService.fetchAll(uid)
        val allPages = binderPageService.fetchAll(uid)
        val allCaps = capPositionService.fetchAll(uid)
        if (allBinders.isEmpty()) return RestoreResult.Empty

        val chosen = chooseBinders(allBinders, allPages, allCaps)
        database.withTransaction {
            binderDao.deleteAll() // kaskada usuwa binder_page i cap_position
            insertRestored(chosen, allPages, allCaps)
        }
        return RestoreResult.Success(chosen.size, allPages.size, allCaps.size)
    }

    // Deduplikacja po nazwie: na nazwę zostaje kopia z największą liczbą kapsli (najpełniejsza).
    // Nie polegamy na kolejności z Firestore — migracje mogły zostawić chude duplikaty.
    private fun chooseBinders(
        allBinders: List<BinderDocument>,
        allPages: List<BinderPageDocument>,
        allCaps: List<CapPositionDocument>
    ): List<BinderDocument> {
        val capsByPageFsId = allCaps.groupingBy { it.binderPageFirestoreId }.eachCount()
        val pagesByBinderFsId = allPages.groupBy { it.binderFirestoreId }
        fun capCount(binderFsId: String): Int =
            pagesByBinderFsId[binderFsId].orEmpty().sumOf { capsByPageFsId[it.firestoreId] ?: 0 }

        return allBinders
            .groupBy { it.name }
            .values
            .map { dups -> dups.maxByOrNull { capCount(it.firestoreId) }!! }
    }

    private suspend fun insertRestored(
        chosenBinders: List<BinderDocument>,
        allPages: List<BinderPageDocument>,
        allCaps: List<CapPositionDocument>
    ) {
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
            // Odtwórz snapshot do cap_cache, by kolekcja renderowała się offline po reinstalacji.
            doc.snapshot?.let { s ->
                capCacheDao.upsertSnapshot(
                    doc.capId, s.name, s.country, s.imageUrl, s.createdAt, s.createdById, s.updatedAt
                )
            }
        }
    }
}

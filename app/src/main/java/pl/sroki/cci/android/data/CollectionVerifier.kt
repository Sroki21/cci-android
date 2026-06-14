package pl.sroki.cci.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import pl.sroki.cci.android.model.toSnapshot
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Statusy zgodne z kolumną cap_cache.catalog_status. */
object CatalogStatus {
    const val UNKNOWN = "unknown"
    const val OK = "ok"
    const val UPDATED = "updated"
    const val SWAPPED = "swapped"
    const val MISSING = "missing"
}

/**
 * Rdzeń weryfikacji kolekcji: porównuje zapisany snapshot z aktualnym stanem katalogu crowncaps
 * przez fingerprint (createdAt + createdById niezmienne; updatedAt/imageUrl/kraj/nazwa = zmiana).
 *
 * Zasada przewodnia: snapshot to źródło prawdy. Przy rozjeździe (swapped/updated) NIE nadpisujemy
 * snapshotu — tylko oznaczamy do decyzji użytkownika. Baseline (brak poprzedniego fingerprintu)
 * zapisuje świeży snapshot bez alarmu.
 */
@Singleton
class CollectionVerifier @Inject constructor(
    private val capsRepository: CapsRepository,
    private val capCacheRepository: CapCacheRepository,
    private val capPositionRepository: CapPositionRepository,
    private val capPositionFirestoreService: CapPositionFirestoreService,
    private val authManager: FirebaseAuthManager,
) {
    private val semaphore = Semaphore(4)

    suspend fun verify(capId: Long): String {
        val stored = capCacheRepository.getOne(capId)
        val cap = try {
            capsRepository.getById(capId.toInt())
        } catch (e: HttpException) {
            if (e.code() == 404) {
                capCacheRepository.markVerified(capId, CatalogStatus.MISSING, now())
                return CatalogStatus.MISSING
            }
            return stored?.catalogStatus ?: CatalogStatus.UNKNOWN // inny błąd HTTP — nie zmieniaj
        } catch (e: IOException) {
            return stored?.catalogStatus ?: CatalogStatus.UNKNOWN // sieć — spróbuj później
        }

        val fresh = cap.toSnapshot()

        // Baseline: brak poprzedniego fingerprintu (np. backfill istniejących 4126) — zapis bez alarmu.
        if (stored?.createdAt == null) {
            writeSnapshot(capId, fresh)
            capCacheRepository.markVerified(capId, CatalogStatus.OK, now())
            pushSnapshot(capId, fresh)
            return CatalogStatus.OK
        }

        // Podmiana tożsamości — pod tym ID jest inny kapsel. Snapshotu NIE ruszamy.
        if (stored.createdAt != fresh.createdAt || stored.createdById != fresh.createdById) {
            capCacheRepository.markVerified(capId, CatalogStatus.SWAPPED, now())
            return CatalogStatus.SWAPPED
        }

        // Zwykła edycja w katalogu. Snapshotu NIE ruszamy — decyzja należy do użytkownika.
        if (stored.updatedAt != fresh.updatedAt || stored.imageUrl != fresh.imageUrl ||
            stored.country != fresh.country || stored.name != fresh.name
        ) {
            capCacheRepository.markVerified(capId, CatalogStatus.UPDATED, now())
            return CatalogStatus.UPDATED
        }

        capCacheRepository.markVerified(capId, CatalogStatus.OK, now())
        return CatalogStatus.OK
    }

    /** Pasywny przebieg: ~limit najdawniej weryfikowanych pozycji. */
    suspend fun runIncremental(limit: Int = 50) {
        runBatch(capCacheRepository.getCapIdsToVerify(limit))
    }

    /** Pełny skan (ręczny i auto-backfill po aktualizacji): wszystkie wstawione pozycje. */
    suspend fun runFullScan(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        runBatch(capPositionRepository.getAllCapIds().distinct(), onProgress, isCancelled)
    }

    private suspend fun runBatch(
        ids: List<Long>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val total = ids.size
        val done = AtomicInteger(0)
        onProgress(0, total)
        coroutineScope {
            ids.map { id ->
                async {
                    if (isCancelled()) return@async
                    semaphore.withPermit {
                        runCatching { verify(id) }
                        delay(THROTTLE_MS) // grzecznościowe tempo wobec crowncaps
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }.awaitAll()
        }
    }

    private suspend fun writeSnapshot(capId: Long, s: pl.sroki.cci.android.data.model.CapSnapshot) =
        capCacheRepository.upsertSnapshot(
            capId, s.name, s.country, s.imageUrl, s.createdAt, s.createdById, s.updatedAt
        )

    private suspend fun pushSnapshot(capId: Long, s: pl.sroki.cci.android.data.model.CapSnapshot) {
        val uid = authManager.uid.value ?: return
        val fsId = runCatching { capPositionRepository.getByCapId(capId)?.firestoreId }.getOrNull() ?: return
        capPositionFirestoreService.scheduleUpdateSnapshot(uid, fsId, s)
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val THROTTLE_MS = 120L
    }
}

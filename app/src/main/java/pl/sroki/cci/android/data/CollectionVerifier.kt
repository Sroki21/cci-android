package pl.sroki.cci.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService
import pl.sroki.cci.android.data.model.CapSnapshot
import pl.sroki.cci.android.model.binder.CatalogStatus
import pl.sroki.cci.android.model.toSnapshot
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wynik skanu kolekcji. [reachedCatalog] to liczba kapsli, dla których katalog naprawdę
 * odpowiedział — licząc 404, bo to również dowód, że serwer był osiągalny. Zero przy skanie ze
 * wszystkimi kapslami znaczy, że katalog był nieosiągalny (offline/403), a nie że kolekcja
 * jest czysta.
 */
data class ScanOutcome(val total: Int, val reachedCatalog: Int) {
    // Przy pustej kolekcji nie ma czego skanować — traktujemy to jako skan „udany".
    val healthy: Boolean get() = total == 0 || reachedCatalog > 0
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

    /**
     * @param onCatalogReached wołane, gdy katalog odpowiedział — zarówno przy udanym pobraniu, jak
     *   i przy 404 (kapsla nie ma, ale serwer był osiągalny). Pozwala [runBatch] odróżnić skan,
     *   który realnie dotknął serwera, od takiego, w którym wszystkie pobrania padły (offline/403)
     *   — bez zmiany zwracanego statusu, na którym opierają się testy i pojedyncze wywołania.
     */
    suspend fun verify(capId: Long, onCatalogReached: () -> Unit = {}): CatalogStatus {
        val stored = capCacheRepository.getOne(capId)
        val cap = try {
            capsRepository.getById(capId)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                // 404 to też odpowiedź katalogu: serwer był osiągalny, kapsel z niego zniknął.
                // Bez tego skan złożony z samych 404 wyglądałby jak skan offline.
                onCatalogReached()
                capCacheRepository.markVerified(capId, CatalogStatus.MISSING, now())
                return CatalogStatus.MISSING
            }
            return stored?.catalogStatus ?: CatalogStatus.UNKNOWN // inny błąd HTTP — nie zmieniaj
        } catch (e: IOException) {
            return stored?.catalogStatus ?: CatalogStatus.UNKNOWN // sieć — spróbuj później
        }
        onCatalogReached()

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

        // Ręcznie wybrany producent (kapsel "-Multiple countries"): surowy cap.country to stały
        // placeholder, więc porównujemy z aktualnym wpisem wybranego producenta, nie z fresh.country.
        val producerId = stored.selectedProducerId
        if (producerId != null) {
            val matchedProducer = cap.producers.firstOrNull { it.id == producerId }
            if (matchedProducer == null) {
                capCacheRepository.markVerified(capId, CatalogStatus.PRODUCER_REMOVED, now())
                return CatalogStatus.PRODUCER_REMOVED
            }
            if (stored.updatedAt != fresh.updatedAt || stored.imageUrl != fresh.imageUrl ||
                stored.name != fresh.name || stored.country != matchedProducer.country.name ||
                stored.producer != matchedProducer.name
            ) {
                capCacheRepository.markVerified(capId, CatalogStatus.UPDATED, now())
                return CatalogStatus.UPDATED
            }
            capCacheRepository.markVerified(capId, CatalogStatus.OK, now())
            return CatalogStatus.OK
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
    ): ScanOutcome =
        runBatch(capPositionRepository.getAllCapIds().distinct(), onProgress, isCancelled)

    private suspend fun runBatch(
        ids: List<Long>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ScanOutcome {
        val total = ids.size
        val done = AtomicInteger(0)
        val reached = AtomicInteger(0)
        onProgress(0, total)
        coroutineScope {
            ids.map { id ->
                async {
                    // Anulowanie sprawdzane PO zdobyciu pozwolenia, nie przed. Wszystkie korutyny
                    // startują naraz, więc warunek przed semaforem przechodzi u każdej w ułamku
                    // sekundy — zanim użytkownik zdąży kliknąć Anuluj. Potem czekają już za
                    // bramką i flaga nie ma czego zatrzymać. Tutaj każda sprawdza ją ponownie,
                    // gdy faktycznie przychodzi jej kolej.
                    semaphore.withPermit {
                        if (isCancelled()) return@withPermit
                        // Awaria pojedynczego kapsla nie przerywa skanu, ale anulowanie musi
                        // przejść dalej — patrz [runCatchingCancellable].
                        runCatchingCancellable { verify(id) { reached.incrementAndGet() } }
                        delay(THROTTLE_MS) // grzecznościowe tempo wobec crowncaps
                    }
                    if (!isCancelled()) onProgress(done.incrementAndGet(), total)
                }
            }.awaitAll()
        }
        return ScanOutcome(total = total, reachedCatalog = reached.get())
    }

    private suspend fun writeSnapshot(capId: Long, s: CapSnapshot) =
        capCacheRepository.upsertSnapshot(
            capId, s.name, s.country, s.imageUrl, s.createdAt, s.createdById, s.updatedAt
        )

    private suspend fun pushSnapshot(capId: Long, s: CapSnapshot) {
        val uid = authManager.uid.value ?: return
        val fsId = runCatchingCancellable {
            capPositionRepository.getByCapId(capId)?.firestoreId
        } ?: return
        capPositionFirestoreService.scheduleUpdateSnapshot(uid, fsId, s)
    }

    /**
     * `runCatching`, które **nie** połyka anulowania.
     *
     * Zwykłe `runCatching` łapie `Throwable`, więc zjada też `CancellationException` rzucone przez
     * anulowaną korutynę — a to psuje kooperatywne anulowanie skanu. Tutaj działało wyłącznie
     * przypadkiem, bo tuż za nim stoi `delay`, które rzuca je ponownie; przesunięcie throttle'a
     * cicho zepsułoby przycisk Anuluj przy pełnym skanie.
     */
    private inline fun <T> runCatchingCancellable(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val THROTTLE_MS = 120L
    }
}

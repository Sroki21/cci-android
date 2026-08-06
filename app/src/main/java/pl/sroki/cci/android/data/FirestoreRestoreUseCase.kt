package pl.sroki.cci.android.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
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
import pl.sroki.cci.android.data.datasource.remote.firestore.ProducerSelection
import pl.sroki.cci.android.data.datasource.remote.firestore.PurchasedCapsFirestoreService
import pl.sroki.cci.android.data.model.BinderCapCount
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RestoreResult {
    /**
     * @param skipped pozycje, których nie dało się wstawić, bo slot (strona, pozycja) był już
     *   zajęty. Niezerowa wartość znaczy, że kapsle wypadły z kolekcji — patrz SkippedPosition.
     */
    data class Success(
        val binders: Int,
        val pages: Int,
        val caps: Int,
        val skipped: List<SkippedPosition> = emptyList()
    ) : RestoreResult
    data object NotLoggedIn : RestoreResult
    data object Empty : RestoreResult
}

/** Pozycja pominięta przy odtwarzaniu wraz z kapslem, który zajął jej slot. */
data class SkippedPosition(
    val capId: Long,
    val position: Int,
    val occupiedByCapId: Long?
)

/**
 * Wynik wstawiania: pozycje realnie utracone oraz nadmiarowe dokumenty Firestore do usunięcia.
 * Kolizja slotu ma dwa różne znaczenia i mylenie ich kosztowałoby dane:
 *  - kapsel ma już pozycję z innego dokumentu -> ten dokument jest duplikatem (do usunięcia),
 *  - kapsel nie ma żadnej pozycji -> wypadł z kolekcji (do zgłoszenia, NIE do usunięcia).
 */
private data class InsertOutcome(
    val skipped: List<SkippedPosition>,
    val redundantDocIds: List<String>
)

@Singleton
class FirestoreRestoreUseCase @Inject constructor(
    @ApplicationContext context: Context,
    private val authManager: FirebaseAuthManager,
    private val database: CciDatabase,
    private val binderDao: BinderDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionDao: CapPositionDao,
    private val capCacheDao: CapCacheDao,
    private val binderService: BinderFirestoreService,
    private val binderPageService: BinderPageFirestoreService,
    private val capPositionService: CapPositionFirestoreService,
    private val purchasedCapsService: PurchasedCapsFirestoreService,
    private val purchasedCapsLocalStore: PurchasedCapsLocalStore
) {
    private companion object {
        const val PREFS_NAME = "sync_state"
        const val KEY_PRODUCER_BACKFILL = "producer_backfill_version"
        // Podbij, gdy backfill ma się wykonać ponownie u wszystkich użytkowników.
        const val PRODUCER_BACKFILL_VERSION = 1
        const val KEY_BINDER_DEDUP = "binder_dedup_version"
        // Podbij, gdy sprzątanie duplikatów ma się wykonać ponownie.
        const val BINDER_DEDUP_VERSION = 1
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val restoreIfEmptyMutex = Mutex()

    /**
     * Sprząta klasery zdublowane przez wcześniejsze wersje odtwarzania, które wstawiały do Roomu
     * każdy dokument z Firestore — także kilka o tej samej nazwie. Dziś odsiewa je [chooseBinders],
     * więc nowych duplikatów nie przybywa i sprzątanie wykonuje się jednorazowo.
     *
     * Dwa świadome ograniczenia, bo poprzednia wersja (`DELETE ... WHERE id NOT IN (SELECT MAX(id)
     * ... GROUP BY name)`) kasowała **dane użytkownika**: leciała przy każdym starcie, więc
     * zabierała też klasery utworzone ręcznie o powtórzonej nazwie, i zostawiała najnowszy wiersz
     * zamiast najpełniejszego, więc razem z duplikatem znikały kapsle (kaskada FK na binder_page
     * i cap_position).
     *
     * Dlatego: usuwamy wyłącznie duplikaty **bez ani jednego kapsla** (przepadają co najwyżej puste
     * strony), a na nazwę zostaje klaser z największą liczbą kapsli — przy remisie najstarszy.
     * Gdy kapsle ma więcej niż jedna kopia, nie ruszamy żadnej i zgłaszamy to do rozstrzygnięcia
     * ręcznego — scalanie zawartości zgadywałoby, gdzie kapsel ma stać.
     */
    suspend fun deduplicateRoomData() {
        if (prefs.getInt(KEY_BINDER_DEDUP, 0) >= BINDER_DEDUP_VERSION) return
        val duplikaty = binderDao.getCapCounts().groupBy { it.name }.values.filter { it.size > 1 }
        for (grupa in duplikaty) {
            val zawartosc = grupa.sortedWith(compareByDescending<BinderCapCount> { it.capCount }.thenBy { it.id })
            val zachowany = zawartosc.first()
            val doUsuniecia = zawartosc.drop(1).filter { it.capCount == 0 }
            doUsuniecia.forEach { binderDao.deleteById(it.id) }
            val niepuste = zawartosc.drop(1).size - doUsuniecia.size
            if (niepuste > 0) {
                val opis = "klaser \"${zachowany.name}\": $niepuste zduplikowanych kopii z kapslami"
                Log.w("CCI_SYNC", "$opis — pozostawione bez zmian")
                Sentry.captureMessage(opis)
            }
            if (doUsuniecia.isNotEmpty()) {
                Log.i("CCI_SYNC", "usunięto ${doUsuniecia.size} pustych duplikatów klasera \"${zachowany.name}\"")
            }
        }
        prefs.edit().putInt(KEY_BINDER_DEDUP, BINDER_DEDUP_VERSION).apply()
    }

    /**
     * Jednorazowo wypycha do Firestore ręczne wybory producenta zapisane jeszcze przez wersję,
     * która ich nie synchronizowała. Bez tego wybory sprzed aktualizacji nadal ginęłyby przy
     * reinstalacji — poprawka sama z siebie działa dopiero dla wyborów robionych od teraz.
     *
     * Zapisy Firestore są kolejkowane offline przez SDK, więc pojedyncze odpalenie wystarcza;
     * flaga w SharedPreferences chroni przed powtarzaniem tych zapisów przy każdym starcie.
     */
    suspend fun backfillProducerSelections(): Int {
        if (prefs.getInt(KEY_PRODUCER_BACKFILL, 0) >= PRODUCER_BACKFILL_VERSION) return 0
        val uid = authManager.uid.value ?: return 0
        var pushed = 0
        capCacheDao.getWithProducerSelection().forEach { cached ->
            val producerId = cached.selectedProducerId ?: return@forEach
            val fsId = capPositionDao.getByCapId(cached.capId)?.firestoreId ?: return@forEach
            capPositionService.scheduleUpdateProducer(
                uid, fsId, ProducerSelection(producerId, cached.producer, cached.country)
            )
            pushed++
        }
        prefs.edit().putInt(KEY_PRODUCER_BACKFILL, PRODUCER_BACKFILL_VERSION).apply()
        Log.i("CCI_SYNC", "backfill wyborów producenta: wypchnięto $pushed")
        return pushed
    }

    /**
     * Uzgadnia listę "zakupionych" z chmurą. Rozmyślnie bez scalania zbiorów: przy pustym
     * zbiorze po jednej stronie kopiujemy w tę stronę, w pozostałych przypadkach nie ruszamy
     * niczego. Scalanie wskrzeszałoby kapsle świadomie usunięte z kolekcji na innym urządzeniu.
     */
    suspend fun syncPurchasedCaps() {
        val uid = authManager.uid.value ?: return
        val zdalne = runCatching { purchasedCapsService.fetch(uid) }.getOrElse {
            Log.w("CCI_SYNC", "nie udało się pobrać listy zakupionych: ${it.message}")
            return
        }
        when {
            // Świeża instalacja: lokalnie pusto, w chmurze jest komplet.
            purchasedCapsLocalStore.isEmpty() && zdalne.isNotEmpty() -> {
                purchasedCapsLocalStore.replaceAllLocally(zdalne)
                Log.i("CCI_SYNC", "odtworzono ${zdalne.size} zakupionych kapsli z Firestore")
            }
            // Instalacja sprzed synchronizacji: lokalnie jest lista, w chmurze jeszcze nic.
            zdalne.isEmpty() && !purchasedCapsLocalStore.isEmpty() -> {
                val lokalne = purchasedCapsLocalStore.getIds()
                purchasedCapsService.scheduleReplaceAll(uid, lokalne)
                Log.i("CCI_SYNC", "backfill: wypchnięto ${lokalne.size} zakupionych kapsli")
            }
        }
        prunePurchasedAlreadyInBinders()
    }

    /**
     * Zdejmuje z listy "zakupionych" kapsle, które mają już pozycję w klaserze — nie są
     * zakupione-ale-nieprzypięte, więc zakładka i tak ich nie pokaże. Osad po wcześniejszej
     * wersji, w której przypięcie nie zdejmowało kapsla z tej listy; wykonuje się po
     * uzgodnieniu z chmurą, żeby czyszczenie objęło też wpisy właśnie stamtąd pobrane.
     */
    private suspend fun prunePurchasedAlreadyInBinders() {
        val przypiete = capPositionDao.getAllCapIds().toSet()
        if (przypiete.isEmpty()) return
        val doUsuniecia = purchasedCapsLocalStore.getIds().intersect(przypiete)
        if (doUsuniecia.isEmpty()) return
        doUsuniecia.forEach { purchasedCapsLocalStore.remove(it) } // zdejmuje też z Firestore
        Log.i("CCI_SYNC", "zdjęto ${doUsuniecia.size} kapsli z listy zakupionych — są w klaserach")
    }

    suspend fun restoreIfEmpty() {
        val uid = authManager.uid.value ?: return
        restoreIfEmptyMutex.withLock {
            if (binderDao.countAll() > 0) return
            val allBinders = binderService.fetchAll(uid)
            val allPages = binderPageService.fetchAll(uid)
            val allCaps = capPositionService.fetchAll(uid)
            val outcome = insertRestored(chooseBinders(allBinders, allPages, allCaps), allPages, allCaps)
            reportSkipped(outcome.skipped)
            removeRedundantDocs(uid, outcome.redundantDocIds)
        }
    }

    /**
     * Pominięta pozycja to kapsel, który wypadł z kolekcji. Wcześniej działo się to bezgłośnie —
     * odtwarzanie meldowało sukces, a użytkownik odkrywał brak dopiero licząc kapsle.
     */
    private fun reportSkipped(skipped: List<SkippedPosition>) {
        if (skipped.isEmpty()) return
        val detail = skipped.joinToString { "cap ${it.capId} (poz. ${it.position}, zajęte przez ${it.occupiedByCapId})" }
        Log.w("CCI_SYNC", "restore pominął ${skipped.size} pozycji: $detail")
        Sentry.captureMessage("Restore pominął ${skipped.size} pozycji: $detail")
    }

    /**
     * Wymuszone ponowne pobranie z Firestore — także gdy lokalna baza nie jest pusta.
     * Bezpieczeństwo: najpierw pobiera komplet z Firestore i tylko gdy się uda oraz nie
     * jest pusty, atomowo (transakcja) czyści lokalne klasery i wstawia świeże dane.
     * Nie kasuje cache krajów/zdjęć (cap_cache) — jest niezależny od umiejscowienia.
     */
    suspend fun restoreFromFirestore(): RestoreResult {
        val uid = authManager.uid.value ?: return RestoreResult.NotLoggedIn
        return restoreIfEmptyMutex.withLock {
            val allBinders = binderService.fetchAll(uid)
            val allPages = binderPageService.fetchAll(uid)
            val allCaps = capPositionService.fetchAll(uid)
            if (allBinders.isEmpty()) return RestoreResult.Empty

            val chosen = chooseBinders(allBinders, allPages, allCaps)
            val outcome = database.withTransaction {
                binderDao.deleteAll() // kaskada usuwa binder_page i cap_position
                insertRestored(chosen, allPages, allCaps)
            }
            reportSkipped(outcome.skipped)
            // Poza transakcją — kasowanie w Firestore nie ma się co wiązać z rollbackiem Roomu.
            removeRedundantDocs(uid, outcome.redundantDocIds)
            RestoreResult.Success(chosen.size, allPages.size, allCaps.size, outcome.skipped)
        }
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
    ): InsertOutcome {
        val collisions = mutableListOf<SkippedPosition>()
        val collidingDocIds = mutableMapOf<Long, MutableList<String>>()
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
            val rowId = capPositionDao.insertOrIgnore(
                CapPosition(
                    binderPageId = parentRoomId,
                    position = doc.position,
                    capId = doc.capId,
                    firestoreId = doc.firestoreId
                )
            )
            // -1 = kolizja UNIQUE(strona, pozycja). Wcześniej ginęło to bez śladu i tak wypadły
            // z kolekcji cztery kapsle, wyparte przez zduplikowane dokumenty pozycji z Firestore.
            if (rowId == -1L) {
                collisions += SkippedPosition(
                    capId = doc.capId,
                    position = doc.position,
                    occupiedByCapId = capPositionDao.getCapIdAt(parentRoomId, doc.position)
                )
                collidingDocIds.getOrPut(doc.capId) { mutableListOf() } += doc.firestoreId
            }
            // Odtwórz snapshot do cap_cache, by kolekcja renderowała się offline po reinstalacji.
            doc.snapshot?.let { s ->
                capCacheDao.upsertSnapshot(
                    doc.capId, s.name, s.country, s.imageUrl, s.createdAt, s.createdById, s.updatedAt
                )
            }
            // Ręczny wybór producenta — musi iść PO snapshocie, bo nadpisuje country wybranym
            // krajem. Bez tego kapsel "-Multiple countries" wracał po reinstalacji do surowego
            // kraju z katalogu, a weryfikacja zgłaszała go jako rozjazd.
            doc.producerSelection?.let { sel ->
                capCacheDao.selectProducer(doc.capId, sel.producerId, sel.producer, sel.country)
            }
        }

        // Klasyfikacja dopiero po całej pętli: dopiero teraz wiadomo, czy kapsel dostał pozycję
        // z któregokolwiek ze swoich dokumentów. Wcześniej nie da się tego stwierdzić, bo
        // zwycięski dokument może wystąpić po kolidującym.
        val lost = mutableListOf<SkippedPosition>()
        val redundant = mutableListOf<String>()
        collisions.forEach { collision ->
            if (capPositionDao.getByCapId(collision.capId) != null) {
                redundant += collidingDocIds[collision.capId].orEmpty()
            } else {
                lost += collision
            }
        }
        return InsertOutcome(lost, redundant.distinct())
    }

    /**
     * Kasuje nadmiarowe dokumenty pozycji. Bez tego duplikaty zostają w Firestore na zawsze,
     * a przy każdym kolejnym odtwarzaniu o slot walczy kilka dokumentów — dziś wygrał właściwy,
     * jutro mógłby wygrać ten, który wypycha inny kapsel z kolekcji.
     */
    private fun removeRedundantDocs(uid: String, docIds: List<String>) {
        if (docIds.isEmpty()) return
        docIds.forEach { capPositionService.scheduleDelete(uid, it) }
        Log.i("CCI_SYNC", "usunięto ${docIds.size} nadmiarowych dokumentów pozycji z Firestore")
    }
}

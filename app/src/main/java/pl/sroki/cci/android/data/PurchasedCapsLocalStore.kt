package pl.sroki.cci.android.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import pl.sroki.cci.android.data.datasource.remote.firestore.PurchasedCapsFirestoreService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kapsle w kolekcji, które nie mają jeszcze pozycji w klaserze (zakładka Zakupione).
 *
 * Zapis idzie dwutorowo — do SharedPreferences i do Firestore. Wcześniej lista istniała
 * wyłącznie lokalnie i ginęła przy reinstalacji, a zakładka odbudowywała się dopiero
 * kropelkowo, przy otwieraniu szczegółów poszczególnych kapsli.
 */
@Singleton
class PurchasedCapsLocalStore @Inject constructor(
    @ApplicationContext context: Context,
    private val firestoreService: PurchasedCapsFirestoreService,
    private val authManager: FirebaseAuthManager
) {
    private val prefs = context.getSharedPreferences("purchased_caps", Context.MODE_PRIVATE)

    fun add(capId: Long) {
        val ids = rawIds().toMutableSet()
        if (!ids.add(capId.toString())) return // już był — bez zbędnego zapisu do chmury
        prefs.edit { putStringSet(KEY, ids) }
        authManager.uid.value?.let { firestoreService.scheduleAdd(it, capId) }
    }

    fun remove(capId: Long) {
        val ids = rawIds().toMutableSet()
        if (!ids.remove(capId.toString())) return
        prefs.edit { putStringSet(KEY, ids) }
        authManager.uid.value?.let { firestoreService.scheduleRemove(it, capId) }
    }

    fun getIds(): Set<Long> = rawIds().mapNotNull { it.toLongOrNull() }.toSet()

    fun isEmpty(): Boolean = rawIds().isEmpty()

    /** Podmiana całości bez wypychania do chmury — używane przy odtwarzaniu z Firestore. */
    fun replaceAllLocally(capIds: Set<Long>) {
        prefs.edit { putStringSet(KEY, capIds.map { it.toString() }.toSet()) }
    }

    private fun rawIds(): Set<String> = prefs.getStringSet(KEY, null) ?: emptySet()

    companion object {
        private const val KEY = "ids"
    }
}

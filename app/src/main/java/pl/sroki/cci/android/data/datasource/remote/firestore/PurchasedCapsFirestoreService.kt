package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup listy kapsli oznaczonych jako "zakupione" — czyli będących w kolekcji, ale jeszcze
 * nieprzypiętych do klasera. Lista żyła wyłącznie w SharedPreferences, więc reinstalacja
 * zostawiała zakładkę Zakupione pustą; odbudowywała się dopiero po otwarciu szczegółów
 * każdego kapsla z osobna.
 *
 * Jeden dokument z tablicą identyfikatorów — zbiór jest mały, a arrayUnion/arrayRemove
 * pozwalają dopisywać i usuwać pojedyncze pozycje bez nadpisywania całości.
 */
@Singleton
class PurchasedCapsFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun doc(uid: String) = firestore.document("users/$uid/meta/purchased_caps")

    fun scheduleAdd(uid: String, capId: Long) {
        doc(uid).set(mapOf(FIELD to FieldValue.arrayUnion(capId)), SetOptions.merge())
    }

    fun scheduleRemove(uid: String, capId: Long) {
        doc(uid).set(mapOf(FIELD to FieldValue.arrayRemove(capId)), SetOptions.merge())
    }

    /** Nadpisanie całości — używane wyłącznie przy jednorazowym backfillu. */
    fun scheduleReplaceAll(uid: String, capIds: Set<Long>) {
        doc(uid).set(mapOf(FIELD to capIds.toList()), SetOptions.merge())
    }

    suspend fun fetch(uid: String): Set<Long> {
        val snapshot = doc(uid).get().await()
        @Suppress("UNCHECKED_CAST")
        val raw = snapshot.get(FIELD) as? List<Any?> ?: return emptySet()
        return raw.mapNotNull { (it as? Number)?.toLong() }.toSet()
    }

    private companion object {
        const val FIELD = "ids"
    }
}

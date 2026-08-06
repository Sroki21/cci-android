package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import pl.sroki.cci.android.data.model.CapSnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapPositionFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun col(uid: String) = firestore.collection("users/$uid/cap_positions")

    /** Patrz [BinderFirestoreService.newDocumentId] — id bez sieci, wysyłka dopiero po Roomie. */
    fun newDocumentId(uid: String): String = col(uid).document().id

    fun scheduleCreate(
        uid: String,
        firestoreId: String,
        binderPageFirestoreId: String,
        position: Int,
        capId: Long,
        snapshot: CapSnapshot? = null,
        producerSelection: ProducerSelection? = null
    ) {
        val ref = col(uid).document(firestoreId)
        // Pola snapshotu prefiksowane "cap" — "updatedAt" jest już zajęte przez znacznik sync.
        val data = buildMap<String, Any?> {
            put("binderPageFirestoreId", binderPageFirestoreId)
            put("position", position)
            put("capId", capId)
            put("updatedAt", FieldValue.serverTimestamp())
            if (snapshot != null) {
                put("capName", snapshot.name)
                put("capCountry", snapshot.country)
                put("capImageUrl", snapshot.imageUrl)
                put("capCreatedAt", snapshot.createdAt)
                put("capCreatedById", snapshot.createdById)
                put("capUpdatedAt", snapshot.updatedAt)
            }
            // Ręczny wybór producenta jest decyzją użytkownika, nie danymi z katalogu — dlatego
            // osobne pola, a nie część snapshotu. Gdyby siedział w CapSnapshot, każde
            // toSnapshot() (które o wyborze nie wie) kasowałoby go pustką.
            if (producerSelection != null) {
                put("capSelectedProducerId", producerSelection.producerId)
                put("capProducer", producerSelection.producer)
                put("capCountry", producerSelection.country)
            }
        }
        ref.set(data)
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    /** Odświeża snapshot w istniejącym dokumencie pozycji (po udanej weryfikacji baseline/ok). */
    fun scheduleUpdateSnapshot(uid: String, firestoreId: String, snapshot: CapSnapshot) {
        col(uid).document(firestoreId).update(
            mapOf(
                "capName" to snapshot.name,
                "capCountry" to snapshot.country,
                "capImageUrl" to snapshot.imageUrl,
                "capCreatedAt" to snapshot.createdAt,
                "capCreatedById" to snapshot.createdById,
                "capUpdatedAt" to snapshot.updatedAt
            )
        )
    }

    /**
     * Utrwala ręczny wybór producenta dla kapsla "-Multiple countries".
     * Bez tego wybór żyje wyłącznie w Roomie i ginie przy reinstalacji aplikacji.
     * Nadpisuje też capCountry, żeby po odtworzeniu kraj i wybrany producent się zgadzały.
     */
    fun scheduleUpdateProducer(uid: String, firestoreId: String, selection: ProducerSelection) {
        col(uid).document(firestoreId).update(
            mapOf(
                "capSelectedProducerId" to selection.producerId,
                "capProducer" to selection.producer,
                "capCountry" to selection.country
            )
        )
    }

    fun scheduleDeleteByPage(uid: String, pageFirestoreId: String) {
        col(uid).whereEqualTo("binderPageFirestoreId", pageFirestoreId).get()
            .addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
    }

    suspend fun fetchAll(uid: String): List<CapPositionDocument> =
        col(uid).get().await().documents.mapNotNull { it.toCapPositionDocument() }
}

data class CapPositionDocument(
    val firestoreId: String,
    val binderPageFirestoreId: String,
    val position: Int,
    val capId: Long,
    val snapshot: CapSnapshot? = null,
    val producerSelection: ProducerSelection? = null
)

/** Ręcznie wybrany producent kapsla "-Multiple countries" wraz z jego krajem. */
data class ProducerSelection(
    val producerId: Int,
    val producer: String,
    val country: String
)

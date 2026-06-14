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

    fun scheduleCreate(
        uid: String,
        binderPageFirestoreId: String,
        position: Int,
        capId: Long,
        snapshot: CapSnapshot? = null
    ): String {
        val ref = col(uid).document()
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
        }
        ref.set(data)
        return ref.id
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

    fun scheduleDeleteByPage(uid: String, pageFirestoreId: String) {
        col(uid).whereEqualTo("binderPageFirestoreId", pageFirestoreId).get()
            .addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
    }

    suspend fun fetchAll(uid: String): List<CapPositionDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            val snapshot = doc.getString("capImageUrl")?.let {
                CapSnapshot(
                    name = doc.getString("capName") ?: "",
                    country = doc.getString("capCountry") ?: "",
                    imageUrl = it,
                    createdAt = doc.getString("capCreatedAt"),
                    createdById = doc.getLong("capCreatedById")?.toInt(),
                    updatedAt = doc.getString("capUpdatedAt")
                )
            }
            CapPositionDocument(
                firestoreId = doc.id,
                binderPageFirestoreId = doc.getString("binderPageFirestoreId") ?: return@mapNotNull null,
                position = (doc.getLong("position") ?: return@mapNotNull null).toInt(),
                capId = doc.getLong("capId") ?: return@mapNotNull null,
                snapshot = snapshot
            )
        }
}

data class CapPositionDocument(
    val firestoreId: String,
    val binderPageFirestoreId: String,
    val position: Int,
    val capId: Long,
    val snapshot: CapSnapshot? = null
)

package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapPositionFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun col(uid: String) = firestore.collection("users/$uid/cap_positions")

    fun scheduleCreate(uid: String, binderPageFirestoreId: String, position: Int, capId: Long): String {
        val ref = col(uid).document()
        ref.set(
            mapOf(
                "binderPageFirestoreId" to binderPageFirestoreId,
                "position" to position,
                "capId" to capId,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        return ref.id
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    fun scheduleDeleteByPage(uid: String, pageFirestoreId: String) {
        col(uid).whereEqualTo("binderPageFirestoreId", pageFirestoreId).get()
            .addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
    }

    suspend fun fetchAll(uid: String): List<CapPositionDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            CapPositionDocument(
                firestoreId = doc.id,
                binderPageFirestoreId = doc.getString("binderPageFirestoreId") ?: return@mapNotNull null,
                position = (doc.getLong("position") ?: return@mapNotNull null).toInt(),
                capId = doc.getLong("capId") ?: return@mapNotNull null
            )
        }
}

data class CapPositionDocument(
    val firestoreId: String,
    val binderPageFirestoreId: String,
    val position: Int,
    val capId: Long
)

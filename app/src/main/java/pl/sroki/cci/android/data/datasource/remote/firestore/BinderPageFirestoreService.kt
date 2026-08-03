package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderPageFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun col(uid: String) = firestore.collection("users/$uid/binder_pages")

    fun scheduleCreate(uid: String, binderFirestoreId: String, pageNumber: Int): String {
        val ref = col(uid).document()
        ref.set(
            mapOf(
                "binderFirestoreId" to binderFirestoreId,
                "pageNumber" to pageNumber,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        return ref.id
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    fun scheduleUpdate(uid: String, firestoreId: String, pageNumber: Int) {
        col(uid).document(firestoreId).update(
            mapOf(
                "pageNumber" to pageNumber,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    suspend fun fetchAll(uid: String): List<BinderPageDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            BinderPageDocument(
                firestoreId = doc.id,
                binderFirestoreId = doc.getString("binderFirestoreId") ?: return@mapNotNull null,
                pageNumber = (doc.getLong("pageNumber") ?: return@mapNotNull null).toInt()
            )
        }
}

data class BinderPageDocument(
    val firestoreId: String,
    val binderFirestoreId: String,
    val pageNumber: Int
)

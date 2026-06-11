package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun col(uid: String) = firestore.collection("users/$uid/binders")

    fun scheduleCreate(uid: String, name: String): String {
        val ref = col(uid).document()
        ref.set(mapOf("name" to name, "updatedAt" to FieldValue.serverTimestamp()))
        return ref.id
    }

    fun scheduleUpdate(uid: String, firestoreId: String, name: String) {
        col(uid).document(firestoreId)
            .update("name", name, "updatedAt", FieldValue.serverTimestamp())
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    suspend fun fetchAll(uid: String): List<BinderDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            BinderDocument(
                firestoreId = doc.id,
                name = doc.getString("name") ?: return@mapNotNull null
            )
        }
}

data class BinderDocument(val firestoreId: String, val name: String)

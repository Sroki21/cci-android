package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {

    private fun col(uid: String) = firestore.collection("users/$uid/binders")

    /**
     * Identyfikator nowego dokumentu. Firestore nadaje go lokalnie, bez sieci i bez zapisu, więc
     * można go wpisać do Roomu ZANIM cokolwiek poleci do chmury — a wysłać dopiero wtedy, gdy
     * zapis lokalny się powiódł. Bez tego rozdziału nieudany insert zostawiał dokument-ducha.
     */
    fun newDocumentId(uid: String): String = col(uid).document().id

    fun scheduleCreate(uid: String, firestoreId: String, name: String) {
        col(uid).document(firestoreId)
            .set(mapOf("name" to name, "updatedAt" to FieldValue.serverTimestamp()))
            .zglosBladZapisu("utworzenie klasera")
    }

    fun scheduleUpdate(uid: String, firestoreId: String, name: String) {
        col(uid).document(firestoreId)
            .update("name", name, "updatedAt", FieldValue.serverTimestamp())
            .zglosBladZapisu("zmiana nazwy klasera")
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete().zglosBladZapisu("usunięcie klasera")
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

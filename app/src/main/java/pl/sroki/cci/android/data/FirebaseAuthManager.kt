package pl.sroki.cci.android.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthManager @Inject constructor(private val auth: FirebaseAuth) {

    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    val uid: StateFlow<String?> = _uid.asStateFlow()

    fun currentUid(): String? = (auth.currentUser?.uid ?: _uid.value)
        .also { if (it != null) _uid.value = it }

    suspend fun ensureSignedIn() {
        if (auth.currentUser != null) {
            _uid.value = auth.currentUser?.uid
        }
        // Brak fallbacku na anonymous — UID pozostaje null do czasu logowania email
    }

    suspend fun signInWithEmail(email: String, password: String) {
        try {
            auth.signInWithEmailAndPassword(email, password).await()
        } catch (_: Exception) {
            // Użytkownik nie istnieje lub inne błędy — próbuj utworzyć konto
            auth.createUserWithEmailAndPassword(email, password).await()
        }
        _uid.value = auth.currentUser?.uid
    }
}

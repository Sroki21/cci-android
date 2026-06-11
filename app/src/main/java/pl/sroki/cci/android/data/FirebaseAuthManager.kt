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

    suspend fun ensureSignedIn() {
        if (auth.currentUser != null) {
            _uid.value = auth.currentUser?.uid
            return
        }
        val result = auth.signInAnonymously().await()
        _uid.value = result.user?.uid
    }
}

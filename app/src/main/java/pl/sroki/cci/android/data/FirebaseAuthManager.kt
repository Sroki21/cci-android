package pl.sroki.cci.android.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.CancellationException
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
        }
        // Brak fallbacku na anonymous — UID pozostaje null do czasu logowania email
    }

    /**
     * Loguje do Firebase poświadczeniami przyjętymi już przez crowncaps (wołane wyłącznie po
     * udanym logowaniu webowym), zakładając konto przy pierwszym użyciu.
     *
     * @param previousPassword hasło zapisane w [CredentialsStore] przed tym logowaniem. Gdy
     *   użytkownik zmieni hasło na crowncaps, logowanie webowe przechodzi z nowym, a Firebase
     *   zna jeszcze poprzednie — wtedy przelogowujemy się starym i podnosimy hasło konta.
     *   Bez tego `uid` zostawał `null`, a że każdy zapis do Firestore stoi pod `if (uid != null)`,
     *   synchronizacja z chmurą milkła na dobre i wracała dopiero po powrocie do starego hasła.
     */
    suspend fun signInWithEmail(email: String, password: String, previousPassword: String? = null) {
        val current = auth.currentUser
        if (current != null && !current.isAnonymous) {
            _uid.value = current.uid
            return
        }
        try {
            auth.signInWithEmailAndPassword(email, password).await()
        } catch (e: FirebaseAuthInvalidUserException) {
            // Konta jeszcze nie ma — pierwsze logowanie na tym e-mailu.
            auth.createUserWithEmailAndPassword(email, password).await()
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            handleRejectedPassword(email, password, previousPassword, e)
        }
        _uid.value = auth.currentUser?.uid
    }

    /**
     * Firebase odrzucił hasło. Z samym kodem błędu nie da się rozstrzygnąć, czy konto ma inne
     * hasło, czy nie istnieje wcale: przy włączonej ochronie przed enumeracją e-maili obie
     * sytuacje wracają jako `ERROR_INVALID_CREDENTIAL`. Dlatego najpierw próba migracji hasła,
     * a dopiero potem założenie konta — kolizja przy zakładaniu jest dowodem, że konto istnieje
     * i hasło było po prostu inne.
     */
    private suspend fun handleRejectedPassword(
        email: String,
        password: String,
        previousPassword: String?,
        cause: FirebaseAuthInvalidCredentialsException
    ) {
        if (previousPassword != null && previousPassword != password &&
            migratePassword(email, password, previousPassword)
        ) {
            return
        }
        try {
            auth.createUserWithEmailAndPassword(email, password).await()
        } catch (collision: FirebaseAuthUserCollisionException) {
            // Konto istnieje, a hasła nie znamy — dalej nic tu nie zrobimy. Wyjątek musi wyjść
            // na zewnątrz, bo cicha porażka odcina synchronizację bez śladu.
            throw cause
        }
    }

    /** @return czy udało się zalogować poprzednim hasłem i podnieść je do aktualnego. */
    private suspend fun migratePassword(
        email: String,
        newPassword: String,
        previousPassword: String
    ): Boolean =
        try {
            auth.signInWithEmailAndPassword(email, previousPassword).await()
            val user = auth.currentUser ?: return false
            // updatePassword wymaga świeżego uwierzytelnienia — logowanie linijkę wyżej je daje.
            user.updatePassword(newPassword).await()
            Log.i("CCI_AUTH", "hasło Firebase podniesione po zmianie hasła na crowncaps")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CCI_AUTH", "migracja hasła Firebase nieudana: ${e.message}")
            false
        }
}

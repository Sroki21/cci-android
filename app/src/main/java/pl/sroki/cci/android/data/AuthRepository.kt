package pl.sroki.cci.android.data

import android.util.Log
import io.sentry.Sentry
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import pl.sroki.cci.android.data.datasource.local.CredentialsStore
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.model.LoginErrorResponse
import pl.sroki.cci.android.model.LoginRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApiService: AuthApiService,
    private val sessionRepository: SessionRepository,
    private val cookieJar: PersistentCookieJar,
    private val credentialsStore: CredentialsStore,
    private val firebaseAuthManager: FirebaseAuthManager,
    private val firestoreRestoreUseCase: FirestoreRestoreUseCase
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    init {
        val hasSession = cookieJar
            .loadForRequest("https://crowncaps.info/".toHttpUrl())
            .any { it.name == "crowncapsinfo-session" }
        val cachedToken = sessionRepository.loadCachedToken()
        // Sesja aktywna jeśli jest cookie LUB zapisany Bearer token (cookie to session cookie — nie
        // przeżywa restartu) LUB zapisane poświadczenia, z których ReauthInterceptor odtworzy sesję.
        sessionRepository.setLoggedIn(
            hasSession || cachedToken != null || credentialsStore.hasCredentials()
        )
        if (cachedToken != null) sessionRepository.setToken(cachedToken)
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            authApiService.initCsrf()
            val response = authApiService.login(LoginRequest(email, password))
            Log.d("CCI_AUTH", "login code=${response.code()}")
            val cookies = cookieJar.loadForRequest("https://crowncaps.info/".toHttpUrl())
            Log.d("CCI_AUTH", "cookies: ${cookies.map { it.name }}")
            when (response.code()) {
                200, 302 -> {
                    // 302: serwer zwraca redirect po udanym logowaniu webowym;
                    // CookieJar zapisał już uwierzytelnioną sesję z tej odpowiedzi.
                    sessionRepository.setLoggedIn(true)
                    sessionRepository.setUserName(email)
                    // Sesja webowa (data/*) wygasa po kilkudziesięciu minutach. Zapisane
                    // poświadczenia pozwalają ReauthInterceptorowi odtworzyć ją po cichu,
                    // zamiast wyrzucać użytkownika na ekran logowania.
                    credentialsStore.save(email, password)
                    runCatching { sessionRepository.fetchAndStoreApiToken(email, password) }
                        .onFailure {
                            Sentry.captureException(it)
                            Log.w("CCI_AUTH", "api token fetch failed: ${it.message}")
                        }
                    runCatching { firebaseAuthManager.signInWithEmail(email, password) }
                        .onFailure { Log.w("CCI_AUTH", "Firebase signInWithEmail failed: ${it.message}") }
                    try {
                        firestoreRestoreUseCase.restoreIfEmpty()
                    } catch (e: Exception) {
                        Sentry.captureException(e)
                        Log.w("CCI_AUTH", "restoreIfEmpty after login failed: ${e.message}")
                    }
                    Result.success(Unit)
                }
                422 -> {
                    val raw = response.errorBody()?.string() ?: ""
                    val err = json.decodeFromString<LoginErrorResponse>(raw)
                    Result.failure(Exception(err.errors["email"]?.firstOrNull() ?: "Błąd logowania"))
                }
                else -> Result.failure(Exception("Błąd logowania: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        try { authApiService.logout() } catch (e: Exception) { }
        cookieJar.clear()
        credentialsStore.clear()
        sessionRepository.setLoggedIn(false)
        sessionRepository.setUserName(null)
        sessionRepository.setToken(null)
    }
}

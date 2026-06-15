package pl.sroki.cci.android.data

import android.util.Log
import io.sentry.Sentry
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.model.LoginErrorResponse
import pl.sroki.cci.android.model.LoginRequest
import pl.sroki.cci.android.model.LoginResponse
import pl.sroki.cci.android.model.TokenRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApiService: AuthApiService,
    private val sessionRepository: SessionRepository,
    private val cookieJar: PersistentCookieJar,
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
        // Sesja aktywna jeśli jest cookie LUB zapisany Bearer token (cookie to session cookie — nie przeżywa restartu)
        sessionRepository.setLoggedIn(hasSession || cachedToken != null)
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
                    runCatching { fetchApiToken(email, password) }
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

    private suspend fun fetchApiToken(email: String, password: String) {
        val cached = sessionRepository.loadCachedToken()
        if (cached != null) {
            Log.d("CCI_AUTH", "api token: reusing cached")
            sessionRepository.setToken(cached)
            return
        }
        val resp = authApiService.apiToken(TokenRequest(email, password, "android"))
        Log.d("CCI_AUTH", "api token code=${resp.code()}")
        val raw = resp.body()?.string()
            ?: resp.errorBody()?.string()
            ?: run { Log.d("CCI_AUTH", "api token: empty body"); return }
        val token = when {
            raw.trimStart().startsWith('"') -> raw.trim().removeSurrounding("\"")
            raw.trimStart().startsWith('{') -> json.decodeFromString<LoginResponse>(raw).token
            else -> raw.trim().takeIf { it.isNotBlank() }
        }
        Log.d("CCI_AUTH", "api token fetched: ${token != null}, raw prefix=${raw.take(40)}")
        sessionRepository.setToken(token)
    }

    suspend fun logout() {
        try { authApiService.logout() } catch (e: Exception) { }
        cookieJar.clear()
        sessionRepository.setLoggedIn(false)
        sessionRepository.setUserName(null)
        sessionRepository.setToken(null)
    }
}

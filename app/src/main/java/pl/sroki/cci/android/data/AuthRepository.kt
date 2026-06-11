package pl.sroki.cci.android.data

import com.franmontiel.persistentcookiejar.PersistentCookieJar
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.model.LoginErrorResponse
import pl.sroki.cci.android.model.LoginRequest
import pl.sroki.cci.android.model.LoginResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApiService: AuthApiService,
    private val sessionRepository: SessionRepository,
    private val cookieJar: PersistentCookieJar
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    init {
        val hasSession = cookieJar
            .loadForRequest("https://crowncaps.info/".toHttpUrl())
            .any { it.name == "crowncapsinfo-session" }
        sessionRepository.setLoggedIn(hasSession)
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            authApiService.initCsrf()
            val response = authApiService.login(LoginRequest(email, password))
            when (response.code()) {
                200 -> {
                    sessionRepository.setLoggedIn(true)
                    sessionRepository.setUserName(email)
                    try {
                        val bodyStr = response.body()?.string()
                        if (!bodyStr.isNullOrBlank() && bodyStr.trimStart().startsWith('{')) {
                            sessionRepository.setToken(json.decodeFromString<LoginResponse>(bodyStr).token)
                        }
                    } catch (e: Exception) { /* brak tokenu w odpowiedzi, kontynuuj */ }
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
        sessionRepository.setLoggedIn(false)
        sessionRepository.setUserName(null)
        sessionRepository.setToken(null)
    }
}

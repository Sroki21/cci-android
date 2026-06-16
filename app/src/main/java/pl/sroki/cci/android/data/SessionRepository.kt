package pl.sroki.cci.android.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import dagger.Lazy
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.model.LoginResponse
import pl.sroki.cci.android.model.TokenRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext context: Context,
    // Lazy: SessionRepository ← NetworkModule.provideAuthOkHttpClient ← BearerTokenInterceptor ← SessionRepository (cykl Hilt)
    private val authApiService: Lazy<AuthApiService>
) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    fun setLoggedIn(value: Boolean) { _isLoggedIn.value = value }

    private val _userName = MutableStateFlow<String?>(prefs.getString("user_name", null))
    val userName: StateFlow<String?> = _userName.asStateFlow()
    fun setUserName(value: String?) {
        _userName.value = value
        prefs.edit().apply {
            if (value != null) putString("user_name", value) else remove("user_name")
            apply()
        }
    }

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    fun setToken(value: String?) {
        _token.value = value
        prefs.edit().apply {
            if (value != null) putString("api_token", value) else remove("api_token")
            apply()
        }
    }

    fun loadCachedToken(): String? = prefs.getString("api_token", null)

    suspend fun fetchAndStoreApiToken(email: String, password: String) {
        val cached = loadCachedToken()
        if (cached != null) {
            Log.d("CCI_AUTH", "api token: reusing cached")
            setToken(cached)
            return
        }
        val resp = authApiService.get().apiToken(TokenRequest(email, password, "android"))
        Log.d("CCI_AUTH", "api token code=${resp.code()}")
        val raw = resp.body()?.string()
            ?: resp.errorBody()?.string()
            ?: run { Log.d("CCI_AUTH", "api token: empty body"); return }
        val token = when {
            raw.trimStart().startsWith('"') -> raw.trim().removeSurrounding("\"")
            raw.trimStart().startsWith('{') -> json.decodeFromString<LoginResponse>(raw).token
            else -> raw.trim().takeIf { it.isNotBlank() }
        }
        Log.d("CCI_AUTH", "api token fetched: ${token != null}, raw length=${raw.length}")
        setToken(token)
    }
}

package pl.sroki.cci.android.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    fun setLoggedIn(value: Boolean) { _isLoggedIn.value = value }

    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName.asStateFlow()
    fun setUserName(value: String?) { _userName.value = value }

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
}

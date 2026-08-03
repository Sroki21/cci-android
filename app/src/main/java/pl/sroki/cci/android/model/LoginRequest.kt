package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    // Backend ustawia wtedy cookie "remember_web_*" z wieloletnim czasem życia. W odróżnieniu od
    // cookie sesji ma ono datę wygaśnięcia, więc PersistentCookieJar zapisuje je na dysk —
    // backend odtwarza z niego sesję po restarcie aplikacji, bez ponownego logowania.
    val remember: Boolean = true
)

package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

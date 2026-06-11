package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(val token: String? = null)

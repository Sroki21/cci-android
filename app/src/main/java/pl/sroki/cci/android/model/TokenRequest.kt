package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(
    val email: String,
    val password: String,
    val deviceName: String
)

package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginErrorResponse(val errors: Map<String, List<String>>)

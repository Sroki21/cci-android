package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class SimilarCapsResponse(
    val id: Long,
    val caps: List<Cap>
)

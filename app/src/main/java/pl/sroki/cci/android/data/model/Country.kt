package pl.sroki.cci.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Country(
    val id: Long,
    val name: String,
    val imageUrl: String
)
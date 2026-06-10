package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class AdditionalImage(
    val id: Int,
    val imageUrl: String,
    val thumbnailImageUrl: String,
    val width: Int,
    val height: Int,
)

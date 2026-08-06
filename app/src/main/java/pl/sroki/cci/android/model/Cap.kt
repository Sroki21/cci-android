package pl.sroki.cci.android.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Cap(
    val id: Long,
    val description: String? = "",
    val country: String,
    val product: String,
    val liner: String,
    val purpose: String,
    val imageUrl: String,
    @kotlinx.serialization.Serializable(with = IsInCollectionSerializer::class)
    val isInCollection: Boolean = false
) {
    companion object {
        const val PER_PAGE = 60
    }
}

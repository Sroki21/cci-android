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

/**
 * Static data
 */

val caps = listOf(
    Cap(
        id = 1L,
        description = "Heineken Dark",
        country = "Netherlands",
        product = "Beer",
        purpose = "Bottle closure",
        liner = "Plastic",
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg",
    ),
    Cap(
        id = 2L,
        description = "Heineken Dark Florida",
        country = "Netherlands",
        product = "Beer",
        purpose = "Bottle closure",
        liner = "Plastic",
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/2.c7900789.jpeg",
    )
)

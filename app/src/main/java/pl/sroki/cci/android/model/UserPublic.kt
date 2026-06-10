package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable
import pl.sroki.cci.android.data.model.Country

@Serializable
data class UserPublic(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val imageUrl: String,
    val active: Boolean,
    val country: Country,
)

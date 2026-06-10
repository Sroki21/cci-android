package pl.sroki.cci.android.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.Serializable
import pl.sroki.cci.android.data.model.Country

@Immutable
@Serializable
data class CapExtended(
    val id: Int,
    val description: String? = "",
    val generic: Boolean = false,
    val picture: Boolean = false,
    val rimtext: String? = "",
    val info: String? = "",
    val country: Country,
    val product: Product,
    val purpose: Purpose,
    val liner: Liner,
    val producers: List<Producer> = listOf(),
    val seriesSortOrder: Int?,
    val series: Series?,
    val periodUsed: PeriodUsed?,
    val properties: List<CapProperty> = listOf(),
    val year: Int?,
    val imageUrl: String,
    val signGroups: List<SignGroup> = listOf(),
    val categories: List<Category> = listOf(),
    val insideImages: List<InsideImage> = listOf(),
    val images: List<AdditionalImage> = listOf(),
    val usersCount: Int,
    val isInCollection: Boolean = false,
    val createdBy: UserPublic? = null,
    @Serializable(with = InstantIso8601Serializer::class)
    val createdAt: Instant
)


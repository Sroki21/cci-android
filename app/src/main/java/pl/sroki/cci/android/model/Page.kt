package pl.sroki.cci.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Page<out T>(
    val data: List<T>,
    @SerialName("last_page")
    val lastPage: Int,
    @SerialName("current_page")
    val currentPage: Int,
    @SerialName("per_page")
    val perPage: Int,
    val total: Int
)

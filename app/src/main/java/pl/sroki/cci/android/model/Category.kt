package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(val id: Int, val name: String) {
    val displayName: String get() = name.trim()
}

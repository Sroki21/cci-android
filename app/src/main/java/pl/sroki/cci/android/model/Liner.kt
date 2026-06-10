package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class Liner(val id: Int, val name: String, val imageUrl: String? = null)

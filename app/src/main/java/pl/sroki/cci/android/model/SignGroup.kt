package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class SignGroup(val id: Int, val groupSigns: List<GroupSign>)

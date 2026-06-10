package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class GroupSign(val id: Int, val position: Int?, val sign: Sign)

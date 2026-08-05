package pl.sroki.cci.android.model.binder

/**
 * Domenowy widok pozycji kapsla w klaserze — bez kluczy obcych Room i bez `firestoreId`.
 */
data class CapSlot(
    val id: Long,
    val binderPageId: Long,
    val position: Int,
    val capId: Long
)

package pl.sroki.cci.android.data.model

/** Klaser wraz z liczbą kapsli stojących na jego stronach — podstawa deduplikacji. */
data class BinderCapCount(
    val id: Long,
    val name: String,
    val capCount: Int
)

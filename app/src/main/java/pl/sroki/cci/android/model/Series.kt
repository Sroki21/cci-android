package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable

@Serializable
data class Series(
    val id: Int,
    val name: String,
    val info: String? = "",
    val total: Int,
    val year: Int?
) {
    fun getDescription(capSortOrder: Int?): String {
        var name = name
        val year = this.year
        if (year != null && year > 0) {
            name += " $year"
        }
        if (total > 0) {
            name += if (capSortOrder != null && capSortOrder > 0) {
                " ($capSortOrder/$total)"
            } else {
                " ($total)"
            }
        }
        return name
    }

}

package pl.sroki.cci.android.model

enum class SearchOperator(val label: String) {
    CONTAINS("Zawiera"),
    EQUALS("Równe"),
    STARTS_WITH("Od")
}

data class AdvancedSearchFilter(
    val idValue: String = "",
    val textValue: String = "",
    val textOperator: SearchOperator = SearchOperator.CONTAINS,
    val countryId: Int? = null,
    val countryName: String = "",
    val producerName: String = "",
    val onlyInCollection: Boolean = false
) {
    fun isEmpty() = idValue.isBlank() && textValue.isBlank()
        && countryId == null && producerName.isBlank() && !onlyInCollection
}

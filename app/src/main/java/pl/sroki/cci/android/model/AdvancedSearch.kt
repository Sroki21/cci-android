package pl.sroki.cci.android.model

enum class SearchOperator(val label: String) {
    CONTAINS("Zawiera"),
    EQUALS("Równe"),
    STARTS_WITH("Zaczyna się od")
}

data class AdvancedSearchFilter(
    val idValue: String = "",
    val textValue: String = "",
    val textOperator: SearchOperator = SearchOperator.CONTAINS,
    val countryId: Int? = null,
    val countryName: String = "",
    val producerValue: String = "",
    val producerOperator: SearchOperator = SearchOperator.CONTAINS,
    val onlyInCollection: Boolean = false
) {
    fun isEmpty() = idValue.isBlank() && textValue.isBlank()
        && countryId == null && producerValue.isBlank() && !onlyInCollection
}

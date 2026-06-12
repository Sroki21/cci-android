package pl.sroki.cci.android.navigation

import pl.sroki.cci.android.model.Category

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Countries : Screen("countries")
    object PictureSearch : Screen("picture-search")
    object PictureSearchResults : Screen("picture-search?categories={id}") {
        fun createUrl(categories: Set<Category>): String {
            val categoryIds = categories.map { it.id }.joinToString(",")
            return "picture-search?categories=$categoryIds"
        }
    }

    object Latest : Screen("latest")
    object Country : Screen("countries/{countryId}?name={name}") {
        fun createUrl(id: Long, name: String) = "countries/$id?name=$name"
    }

    object CapDetail : Screen("caps/{capId}") {
        fun createUrl(id: Long) = "caps/$id"
    }

    object QuickSearchResults : Screen("caps/search?query={query}") {
        fun createUrl(query: String) = "caps/search?query=$query"
    }

    object AdvancedSearch : Screen("advanced-search")
    object Purchased : Screen("purchased")
    object Login : Screen("login")
    object Binders : Screen("binders")
}
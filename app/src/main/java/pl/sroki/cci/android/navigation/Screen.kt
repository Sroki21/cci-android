package pl.sroki.cci.android.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Countries : Screen("countries")
    object PictureSearch : Screen("picture-search")
    object Latest : Screen("latest")
    object Country : Screen("countries/{countryId}?name={name}") {
        fun createUrl(id: Long, name: String) = "countries/$id?name=${Uri.encode(name)}"
    }

    object CapDetail : Screen("caps/{capId}") {
        fun createUrl(id: Long) = "caps/$id"
    }

    // Uri.encode jest tu obowiazkowe: bez niego "&" w frazie rozpoczynal kolejny parametr,
    // a wszystko po nim przepadalo — wyszukiwarka po cichu szukala czegos innego, niz wpisano.
    object QuickSearchResults : Screen("caps/search?query={query}") {
        fun createUrl(query: String) = "caps/search?query=${Uri.encode(query)}"
    }

    object OwnedCountries : Screen("owned-countries")

    object CountryOwnedCaps : Screen("owned-caps?country={country}") {
        fun createUrl(country: String) = "owned-caps?country=${Uri.encode(country)}"
    }

    object AdvancedSearch : Screen("advanced-search")
    object AdvancedSearchByProducer : Screen("advanced-search?producer={producer}") {
        fun createUrl(producerName: String) = "advanced-search?producer=${Uri.encode(producerName)}"
    }
    object Purchased : Screen("purchased")
    object Login : Screen("login")
    object Clearance : Screen("clearance")
    object Binders : Screen("binders")
    object Statistics : Screen("statistics")
    object LocationsMap : Screen("locations-map")
    object CollectionVerification : Screen("collection-verification")
}
package pl.sroki.cci.android.model

import kotlinx.serialization.Serializable
import pl.sroki.cci.android.data.model.Country

@Serializable
data class Producer(
    val id: Int,
    val name: String,
    val city: String? = "",
    val country: Country,
    val website: String? = null
) {
    fun getLocation(): String {
        var location = country.name
        // API zwraca brak miasta jako pusty string, nie null — samo `city?.let` dawało pustą
        // linię nad krajem dla każdego producenta bez podanego miasta.
        city?.takeIf { it.isNotBlank() }?.let { location = it + "\n" + location }
        return location
    }
}
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
        city?.let { location = it + "\n" + location }
        return location
    }
}
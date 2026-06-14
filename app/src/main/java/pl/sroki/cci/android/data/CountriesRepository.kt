package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.local.dao.CountryFlagDao
import pl.sroki.cci.android.data.datasource.local.entity.CountryFlag
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import javax.inject.Inject

class CountriesRepository @Inject constructor(
    private val countryApiService: CountryApiService,
    private val countryFlagDao: CountryFlagDao
) {
    suspend fun getCountries(): List<Country> {
        return countryApiService.getCountries()
    }

    /**
     * Mapa nazwa kraju -> URL flagi. Czyta najpierw lokalny cache (Room); gdy pusty,
     * pobiera z API i zapisuje, więc kolejne wejścia są w pełni offline/szybkie.
     */
    suspend fun getFlagMap(): Map<String, String> {
        val cached = countryFlagDao.getAll()
        if (cached.isNotEmpty()) return cached.associate { it.name to it.imageUrl }

        val fetched = runCatching { countryApiService.getCountries() }.getOrNull() ?: return emptyMap()
        countryFlagDao.upsertAll(fetched.map { CountryFlag(it.name, it.imageUrl) })
        return fetched.associate { it.name to it.imageUrl }
    }

    /**
     * Mapa nazwa kraju (ang.) -> kod ISO 3166-1 alpha-2 (lowercase), wyłuskany z nazwy pliku flagi
     * (np. ".../flags/PL.png" -> "pl"). Klucze do dopasowania z id ścieżek na mapie świata.
     * Pomija pseudo-kody historyczne/specjalne (np. MI, UN, CS, SU) — nie mają regionu na mapie,
     * więc po prostu nie zostaną dopasowane.
     */
    suspend fun getIsoMap(): Map<String, String> =
        getFlagMap().mapNotNull { (name, url) ->
            isoFromFlagUrl(url)?.let { name to it }
        }.toMap()

    private fun isoFromFlagUrl(url: String): String? {
        val file = url.substringAfterLast('/').substringBeforeLast('.')
        return file.takeIf { it.length == 2 && it.all(Char::isLetter) }?.lowercase()
    }
}

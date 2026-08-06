package pl.sroki.cci.android.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import pl.sroki.cci.android.data.datasource.local.dao.CountryFlagDao
import pl.sroki.cci.android.data.datasource.local.entity.CountryFlag
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CountriesRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val countryApiService: CountryApiService,
    private val countryFlagDao: CountryFlagDao
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getCountries(): List<Country> {
        return countryApiService.getCountries()
    }

    /**
     * Mapa nazwa kraju -> URL flagi. Czyta lokalny cache (Room), więc kolejne wejścia są szybkie
     * i działają offline.
     *
     * Cache ma teraz termin ważności. Wcześniej warunek brzmiał „jest cokolwiek w bazie — oddaj to"
     * i raz pobrana lista krajów zostawała **na zawsze**: nowy kraj w katalogu nigdy nie dostawał
     * flagi, a ponieważ [getIsoMap] buduje się z tej samej mapy, nie podświetlał się też na mapie
     * świata. Nie było ani TTL, ani ręcznego odświeżenia.
     *
     * Nieudane pobranie nie kasuje tego, co już mamy — przeterminowany cache jest lepszy niż nic.
     * Dopiero brak sieci PRZY pustym cache jest błędem i leci wyżej: wcześniej zwracana pustka
     * dawała ekran „masz 0 kapsli" zamiast informacji, że nie udało się nic pobrać.
     */
    suspend fun getFlagMap(): Map<String, String> {
        val cached = countryFlagDao.getAll()
        val wiekCache = System.currentTimeMillis() - prefs.getLong(KEY_FLAGS_FETCHED_AT, 0L)
        if (cached.isNotEmpty() && wiekCache < TTL_MS) return cached.asFlagMap()

        val fetched = try {
            countryApiService.getCountries()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CCI_UI", "nie udało się odświeżyć listy krajów: ${e.message}")
            if (cached.isNotEmpty()) return cached.asFlagMap()
            throw e
        }
        countryFlagDao.upsertAll(fetched.map { CountryFlag(it.name, it.imageUrl) })
        prefs.edit().putLong(KEY_FLAGS_FETCHED_AT, System.currentTimeMillis()).apply()
        return fetched.associate { it.name to it.imageUrl }
    }

    private fun List<CountryFlag>.asFlagMap(): Map<String, String> =
        associate { it.name to it.imageUrl }

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
        val iso = file.takeIf { it.length == 2 && it.all(Char::isLetter) }?.lowercase() ?: return null
        return FLAG_ISO_ALIASES[iso] ?: iso
    }

    private companion object {
        const val PREFS_NAME = "countries_cache"
        const val KEY_FLAGS_FETCHED_AT = "flags_fetched_at"
        // Lista krajów w katalogu zmienia się rzadko, więc tydzień wystarcza, żeby nowy kraj
        // dostał flagę bez odpytywania API przy każdym wejściu na ekran.
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        // crowncaps.info używa dla kilku krajów nazw plików flag innych niż aktualny
        // ISO 3166-1 alpha-2 (używany przez id ścieżek na mapie świata) — potwierdzone
        // przez porównanie pełnej listy krajów z id-kami w assets/world_map.svg:
        // "UK" (potoczny skrót) zamiast "GB", "SF" (stary kod "Suomi/Finland") zamiast "FI".
        val FLAG_ISO_ALIASES = mapOf(
            "uk" to "gb",
            "sf" to "fi",
        )
    }
}

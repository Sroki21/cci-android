package pl.sroki.cci.android.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.dao.CountryFlagDao
import pl.sroki.cci.android.data.datasource.local.entity.CountryFlag
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import pl.sroki.cci.android.data.model.Country
import java.io.IOException

/**
 * Cache flag nie miał żadnej inwalidacji: `if (cached.isNotEmpty()) return cached` znaczyło, że raz
 * pobrana lista krajów zostaje na zawsze — nowy kraj w katalogu nigdy nie dostawał flagi ani nie
 * podświetlał się na mapie świata. Awaria pobierania przy pustym cache zwracała pustą mapę, więc
 * ekran mapy pokazywał „0 kapsli" zamiast błędu.
 */
class CountriesRepositoryTest {

    private companion object {
        const val TYDZIEN = 7L * 24 * 60 * 60 * 1000
    }

    private lateinit var dao: CountryFlagDao
    private lateinit var api: CountryApiService
    private lateinit var repo: CountriesRepository
    private var pobranoO: Long = 0L
    private var teraz: Long = 0L

    @Before
    fun setUp() {
        pobranoO = 0L
        teraz = System.currentTimeMillis()

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val wartosc = slot<Long>()
        every { editor.putLong(any(), capture(wartosc)) } answers { pobranoO = wartosc.captured; editor }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getLong(any(), any()) } answers { pobranoO }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        dao = mockk(relaxed = true)
        api = mockk()
        repo = CountriesRepository(context, api, dao)
    }

    private fun kraj(name: String, iso: String) =
        Country(id = 1, name = name, imageUrl = "https://crowncaps.info/flags/$iso.png")

    @Test
    fun `swiezy cache nie rusza sieci`() = runTest {
        pobranoO = teraz
        coEvery { dao.getAll() } returns listOf(CountryFlag("Polska", "https://f/PL.png"))

        val wynik = repo.getFlagMap()

        assertEquals(mapOf("Polska" to "https://f/PL.png"), wynik)
        coVerify(exactly = 0) { api.getCountries() }
    }

    /** Sedno E6: po terminie ważności lista musi zostać odświeżona, żeby nowy kraj dostał flagę. */
    @Test
    fun `przeterminowany cache jest odswiezany z API`() = runTest {
        pobranoO = teraz - TYDZIEN - 1
        coEvery { dao.getAll() } returns listOf(CountryFlag("Polska", "https://f/PL.png"))
        coEvery { api.getCountries() } returns listOf(kraj("Polska", "PL"), kraj("Malta", "MT"))

        val wynik = repo.getFlagMap()

        assertEquals(setOf("Polska", "Malta"), wynik.keys)
        coVerify(exactly = 1) { dao.upsertAll(any()) }
    }

    @Test
    fun `nieudane odswiezenie oddaje stary cache zamiast pustki`() = runTest {
        pobranoO = teraz - TYDZIEN - 1
        coEvery { dao.getAll() } returns listOf(CountryFlag("Polska", "https://f/PL.png"))
        coEvery { api.getCountries() } throws IOException("Brak połączenia")

        val wynik = repo.getFlagMap()

        assertEquals(mapOf("Polska" to "https://f/PL.png"), wynik)
    }

    /** Pusty cache plus brak sieci to błąd, a nie „w katalogu nie ma krajów". */
    @Test
    fun `pusty cache i brak sieci zglasza blad`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        coEvery { api.getCountries() } throws IOException("Brak połączenia")

        val wynik = runCatching { repo.getFlagMap() }

        assertTrue(wynik.isFailure)
    }

    @Test
    fun `iso wylusluje sie z nazwy pliku flagi wraz z aliasami`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        coEvery { api.getCountries() } returns listOf(
            kraj("Polska", "PL"),
            kraj("Wielka Brytania", "UK"), // alias -> gb
            kraj("Finlandia", "SF"),       // alias -> fi
            kraj("Międzynarodowe", "MI"),  // pseudo-kod bez regionu na mapie
        )

        val iso = repo.getIsoMap()

        assertEquals("pl", iso["Polska"])
        assertEquals("gb", iso["Wielka Brytania"])
        assertEquals("fi", iso["Finlandia"])
        // MI ma dwie litery, więc trafia do mapy — na mapie świata po prostu nie ma takiego id.
        assertEquals("mi", iso["Międzynarodowe"])
    }
}

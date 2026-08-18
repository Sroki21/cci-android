package pl.sroki.cci.android.data

import com.franmontiel.persistentcookiejar.PersistentCookieJar
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.CredentialsStore
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import retrofit2.Response

/**
 * Sedno testu: przed cichym logowaniem trzeba porzucić stare cookies sesji.
 *
 * Bez tego ciche logowanie wracało z 200, a ponowione żądanie i tak dostawało 401 — aż do
 * restartu procesu. Odtworzone na urządzeniu 2026-08-18: gdy sesja i `cf_clearance` wygasną
 * naraz, do jara trafia gościnna sesja z WebView i zostaje tam obok tej z logowania.
 */
class SessionRefresherTest {

    private val baseUrl = "https://crowncaps.info"
    private val url: HttpUrl = baseUrl.toHttpUrl()

    private lateinit var authApiService: AuthApiService
    private lateinit var credentialsStore: CredentialsStore
    private lateinit var sessionRepository: SessionRepository
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var refresher: SessionRefresher

    private fun cookie(name: String, value: String = "x", expiresAt: Long = FAR_FUTURE) =
        Cookie.Builder()
            .name(name)
            .value(value)
            .hostOnlyDomain(url.host)
            .path("/")
            .secure()
            .expiresAt(expiresAt)
            .build()

    @Before
    fun setUp() {
        authApiService = mockk(relaxed = true)
        credentialsStore = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        cookieJar = mockk(relaxed = true)

        every { credentialsStore.load() } returns
            CredentialsStore.Credentials("kolekcjoner@example.com", "haslo")
        coEvery { authApiService.initCsrf() } returns Response.success(Unit)
        coEvery { authApiService.login(any()) } returns Response.success(
            "".toResponseBody(null)
        )

        refresher = SessionRefresher(
            authApiService, credentialsStore, sessionRepository, cookieJar, baseUrl
        )
    }

    private fun jarContains(vararg cookies: Cookie) {
        every { cookieJar.loadForRequest(any()) } returns cookies.toList()
    }

    /** Cookies przekazane do [PersistentCookieJar.saveFromResponse] przy ostatnim wywołaniu. */
    private fun savedCookies(): List<Cookie> {
        val slot = slot<List<Cookie>>()
        verify { cookieJar.saveFromResponse(any(), capture(slot)) }
        return slot.captured
    }

    @Test
    fun `stare cookies sesji sa wygaszane przed logowaniem`() = runTest {
        jarContains(cookie("crowncapsinfo-session", "goscinna"), cookie("XSRF-TOKEN", "stary"))

        val wynik = refresher.reauthenticate()

        assertEquals(ReauthResult.SUCCESS, wynik)
        val zapisane = savedCookies()
        assertEquals(
            setOf("crowncapsinfo-session", "XSRF-TOKEN"),
            zapisane.map { it.name }.toSet()
        )
        assertTrue(
            "wszystkie muszą mieć datę w przeszłości, inaczej jar ich nie wymiecie",
            zapisane.all { it.expiresAt < System.currentTimeMillis() }
        )
    }

    @Test
    fun `cf_clearance przezywa ciche logowanie`() = runTest {
        // Wyczyszczenie clearance kosztowałoby kolejny challenge Cloudflare przy następnym
        // żądaniu — a to właśnie challenge wpędził nas w ten stan.
        jarContains(cookie("crowncapsinfo-session"), cookie("cf_clearance", "wazne"))

        refresher.reauthenticate()

        assertEquals(listOf("crowncapsinfo-session"), savedCookies().map { it.name })
    }

    @Test
    fun `wygaszona kopia zachowuje tozsamosc cookie`() = runTest {
        // Jar rozróżnia wpisy po (nazwa, domena, ścieżka, secure, hostOnly). Kopia z inną
        // tożsamością dołożyłaby trzeci wpis zamiast zastąpić ten zatruty.
        jarContains(cookie("crowncapsinfo-session"))

        refresher.reauthenticate()

        val kopia = savedCookies().single()
        assertEquals(url.host, kopia.domain)
        assertEquals("/", kopia.path)
        assertTrue("host-only", kopia.hostOnly)
        assertTrue("secure", kopia.secure)
    }

    @Test
    fun `pusty jar nie wywoluje zapisu`() = runTest {
        jarContains()

        refresher.reauthenticate()

        verify(exactly = 0) { cookieJar.saveFromResponse(any(), any()) }
    }

    @Test
    fun `brak poswiadczen nie rusza jara`() = runTest {
        every { credentialsStore.load() } returns null
        jarContains(cookie("crowncapsinfo-session"))

        val wynik = refresher.reauthenticate()

        assertEquals(ReauthResult.NO_CREDENTIALS, wynik)
        verify(exactly = 0) { cookieJar.saveFromResponse(any(), any()) }
    }

    @Test
    fun `odrzucone poswiadczenia zwracaja REJECTED`() = runTest {
        coEvery { authApiService.login(any()) } returns Response.error(
            401,
            "".toResponseBody(null)
        )
        jarContains(cookie("crowncapsinfo-session"))

        assertEquals(ReauthResult.REJECTED, refresher.reauthenticate())
    }

    @Test
    fun `padnieta siec nie wylogowuje`() = runTest {
        coEvery { authApiService.login(any()) } throws java.io.IOException("brak sieci")
        jarContains(cookie("crowncapsinfo-session"))

        assertEquals(ReauthResult.UNAVAILABLE, refresher.reauthenticate())
    }

    private companion object {
        val FAR_FUTURE = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
    }
}

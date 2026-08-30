package pl.sroki.cci.android.data.datasource.remote.auth

import com.franmontiel.persistentcookiejar.PersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sedno testu: przed każdym logowaniem trzeba porzucić stare cookies sesji, a duplikat wpisu
 * w jarze musi być wykrywalny.
 *
 * Bez czyszczenia logowanie wracało z 200, a ponowione żądanie i tak dostawało 401 — aż do
 * restartu procesu. Odtworzone na urządzeniu 2026-08-18: gdy sesja i `cf_clearance` wygasną
 * naraz, do jara trafiała gościnna sesja z WebView i zostawała tam obok tej z logowania.
 */
class WebSessionCookiesTest {

    private val baseUrl = "https://crowncaps.info"
    private val url: HttpUrl = baseUrl.toHttpUrl()

    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var cookies: WebSessionCookies

    private fun cookie(
        name: String,
        value: String = "x",
        path: String = "/",
        secure: Boolean = true,
    ) = Cookie.Builder()
        .name(name)
        .value(value)
        .hostOnlyDomain(url.host)
        .path(path)
        .expiresAt(FAR_FUTURE)
        .let { if (secure) it.secure() else it }
        .build()

    @Before
    fun setUp() {
        cookieJar = mockk(relaxed = true)
        cookies = WebSessionCookies(cookieJar, baseUrl)
    }

    private fun jarContains(vararg zawartosc: Cookie) {
        every { cookieJar.loadForRequest(any()) } returns zawartosc.toList()
    }

    /** Cookies przekazane do [PersistentCookieJar.saveFromResponse] przy ostatnim wywołaniu. */
    private fun savedCookies(): List<Cookie> {
        val slot = slot<List<Cookie>>()
        verify { cookieJar.saveFromResponse(any(), capture(slot)) }
        return slot.captured
    }

    @Test
    fun `stare cookies sesji sa wygaszane`() {
        jarContains(cookie("crowncapsinfo-session", "goscinna"), cookie("XSRF-TOKEN", "stary"))

        assertEquals(2, cookies.drop())

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
    fun `cf_clearance przezywa czyszczenie`() {
        // Wyczyszczenie clearance kosztowałoby kolejny challenge Cloudflare przy następnym
        // żądaniu — a to właśnie challenge wpędził nas w ten stan.
        jarContains(cookie("crowncapsinfo-session"), cookie("cf_clearance", "wazne"))

        cookies.drop()

        assertEquals(listOf("crowncapsinfo-session"), savedCookies().map { it.name })
    }

    @Test
    fun `wygaszona kopia zachowuje tozsamosc cookie`() {
        // Jar rozróżnia wpisy po (nazwa, domena, ścieżka, secure, hostOnly). Kopia z inną
        // tożsamością dołożyłaby trzeci wpis zamiast zastąpić ten zatruty.
        jarContains(cookie("crowncapsinfo-session"))

        cookies.drop()

        val kopia = savedCookies().single()
        assertEquals(url.host, kopia.domain)
        assertEquals("/", kopia.path)
        assertTrue("host-only", kopia.hostOnly)
        assertTrue("secure", kopia.secure)
    }

    @Test
    fun `oba warianty zatrutego wpisu sa czyszczone`() {
        // Dokładnie stan z urządzenia: cookie zbudowane przez nas z WebView (secure, path "/")
        // i to od serwera (inna ścieżka) to dla jara dwa wpisy — oba lecą w nagłówku Cookie.
        jarContains(
            cookie("crowncapsinfo-session", "goscinna"),
            cookie("crowncapsinfo-session", "zalogowana", path = "/data")
        )

        assertEquals(2, cookies.drop())
        assertEquals(setOf("/", "/data"), savedCookies().map { it.path }.toSet())
    }

    @Test
    fun `pusty jar nie wywoluje zapisu`() {
        jarContains()

        assertEquals(0, cookies.drop())

        verify(exactly = 0) { cookieJar.saveFromResponse(any(), any()) }
    }

    @Test
    fun `duplikat cookie sesji jest wykrywany`() {
        jarContains(
            cookie("crowncapsinfo-session", "goscinna"),
            cookie("crowncapsinfo-session", "zalogowana", path = "/data")
        )

        assertTrue(cookies.isDuplicated())
    }

    @Test
    fun `pojedyncza sesja to nie duplikat`() {
        jarContains(cookie("crowncapsinfo-session"), cookie("XSRF-TOKEN"), cookie("cf_clearance"))

        assertFalse(cookies.isDuplicated())
    }

    @Test
    fun `pusty jar to nie duplikat`() {
        jarContains()

        assertFalse(cookies.isDuplicated())
    }

    private companion object {
        val FAR_FUTURE = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
    }
}

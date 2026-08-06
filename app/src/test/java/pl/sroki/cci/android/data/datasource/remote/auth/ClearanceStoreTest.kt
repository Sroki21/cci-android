package pl.sroki.cci.android.data.datasource.remote.auth

import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testujemy wyłącznie czystą funkcję [ClearanceStore.parseWebViewCookies] — reszta magazynu
 * dotyka Androida (CookieManager, WebSettings, SharedPreferences). Instancję powołujemy z
 * zamockowanymi zależnościami, ale wołamy tylko tę jedną metodę, która ich nie używa.
 */
class ClearanceStoreTest {

    private val url = "https://crowncaps.info".toHttpUrl()
    private val store = ClearanceStore(
        context = mockk(relaxed = true),
        cookieJar = mockk(relaxed = true),
        baseUrl = "https://crowncaps.info",
    )
    private val now = 1_800_000_000_000L

    @Test
    fun `wyciaga cf_clearance i sesje z lancucha CookieManagera`() {
        val raw = "cf_clearance=abc.def-123; XSRF-TOKEN=tok; laravel_session=sess"

        val cookies = store.parseWebViewCookies(raw, url, now)

        assertEquals(listOf("cf_clearance", "XSRF-TOKEN", "laravel_session"), cookies.map { it.name })
        assertEquals("abc.def-123", cookies.first { it.name == "cf_clearance" }.value)
    }

    @Test
    fun `cookies sa host-only, secure i z data wygasniecia`() {
        val cookie = store.parseWebViewCookies("cf_clearance=xyz", url, now).single()

        assertEquals("crowncaps.info", cookie.domain)
        assertTrue("host-only", cookie.hostOnly)
        assertTrue("secure", cookie.secure)
        assertTrue("persistent (ma wygasniecie)", cookie.persistent)
        assertTrue("wygasa w przyszlosci", cookie.expiresAt > now)
    }

    @Test
    fun `wartosc z zakodowanym znakiem rownosci nie jest obcinana`() {
        // cf_clearance bywa zakodowany base64 z '=' na koncu — dzielimy po pierwszym '='.
        val cookie = store.parseWebViewCookies("cf_clearance=aGVsbG8=", url, now).single()

        assertEquals("aGVsbG8=", cookie.value)
    }

    @Test
    fun `pomija puste i zdeformowane pary`() {
        val cookies = store.parseWebViewCookies("; =beznazwy; cf_clearance=ok; smieci", url, now)

        assertEquals(listOf("cf_clearance"), cookies.map { it.name })
    }

    @Test
    fun `pusty lancuch daje pusta liste`() {
        assertTrue(store.parseWebViewCookies("", url, now).isEmpty())
    }

    @Test
    fun `brak wartosci po znaku rownosci daje pusty string, nie null`() {
        val cookie = store.parseWebViewCookies("cf_clearance=", url, now).single()

        assertEquals("", cookie.value)
        assertNull(null)
    }

    private fun webViewCookies() = store.parseWebViewCookies(
        "cf_clearance=abc; crowncapsinfo-session=goscinna; XSRF-TOKEN=tok",
        url,
        now,
    )

    @Test
    fun `gdy aplikacja ma sesje, goscinna sesja z WebView nie jest przenoszona`() {
        // Sedno błędu 403: WebView otwiera się na sam challenge, użytkownik jest tam gościem.
        val transferable = store.selectTransferable(webViewCookies(), appHasSession = true)

        assertEquals(listOf("cf_clearance"), transferable.map { it.name })
    }

    @Test
    fun `gdy aplikacja nie ma sesji, przenosimy takze sesje z WebView`() {
        // Użytkownik mógł zalogować się na stronie — wtedy ta sesja jest jedyną, jaką mamy.
        val transferable = store.selectTransferable(webViewCookies(), appHasSession = false)

        assertEquals(
            listOf("cf_clearance", "crowncapsinfo-session", "XSRF-TOKEN"),
            transferable.map { it.name },
        )
    }

    @Test
    fun `clearance przechodzi nawet gdy WebView nie ma zadnych cookies sesji`() {
        val cookies = store.parseWebViewCookies("cf_clearance=abc", url, now)

        assertEquals(1, store.selectTransferable(cookies, appHasSession = true).size)
    }

    @Test
    fun `to samo clearance, ktore bramka odrzucila, nie jest swieze`() {
        // Sedno migających okienek: WebView oddaje cookie, które już mieliśmy i które przed
        // chwilą zostało unieważnione — ekran zamykał się na stronie „Just a moment…".
        val cookies = store.parseWebViewCookies("cf_clearance=stare", url, now)

        assertFalse(store.isFreshClearance(cookies, rejected = "stare"))
    }

    @Test
    fun `nowe clearance po rozwiazanym challengu jest swieze`() {
        val cookies = store.parseWebViewCookies("cf_clearance=nowe", url, now)

        assertTrue(store.isFreshClearance(cookies, rejected = "stare"))
    }

    @Test
    fun `pierwszy challenge bez wczesniejszego clearance uznaje kazde za swieze`() {
        val cookies = store.parseWebViewCookies("cf_clearance=pierwsze", url, now)

        assertTrue(store.isFreshClearance(cookies, rejected = null))
    }

    @Test
    fun `brak clearance nigdy nie jest swiezy`() {
        val cookies = store.parseWebViewCookies("crowncapsinfo-session=abc", url, now)

        assertFalse(store.isFreshClearance(cookies, rejected = null))
    }
}

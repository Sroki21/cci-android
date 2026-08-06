package pl.sroki.cci.android.data.datasource.remote.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Most między Cloudflare Managed Challenge a klientem OkHttp.
 *
 * Serwis crowncaps.info stoi za bramką Cloudflare, która na ruch nieprzeglądarkowy odpowiada
 * `403` z nagłówkiem `Cf-Mitigated: challenge` i stroną „Just a moment…". Przejść ją potrafi
 * tylko przeglądarka wykonująca JavaScript; przechodzi ją ręcznie użytkownik w [WebView], a wtedy
 * Cloudflare ustawia cookie `cf_clearance`. Ten magazyn:
 *
 *  1. trzyma User-Agent zgodny z tym, którego używa WebView — `cf_clearance` jest ważny **tylko**
 *     dla pary (UA, IP), która go uzyskała, więc OkHttp musi wysyłać dokładnie ten sam UA;
 *  2. przenosi cookies z [CookieManager] WebView do [PersistentCookieJar] OkHttpa, żeby żądania
 *     API niosły `cf_clearance` (oraz świeżą sesję webową, jeśli użytkownik przy okazji się
 *     zalogował na stronie);
 *  3. wystawia [challengeRequired], które warstwa nawigacji obserwuje, by pokazać ekran WebView,
 *     gdy interceptor wykryje challenge.
 *
 * Działanie odbywa się za zgodą administratora serwisu.
 */
@Singleton
class ClearanceStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cookieJar: PersistentCookieJar,
    private val baseUrl: String,
) {
    private val prefs = context.getSharedPreferences("clearance", Context.MODE_PRIVATE)

    // UA pobrany raz z WebView. Ustawiamy go od startu, żeby OkHttp od pierwszego żądania
    // przedstawiał się jak przeglądarka i pasował do UA, którym WebView rozwiąże challenge.
    @Volatile
    var userAgent: String? = prefs.getString("user_agent", null)
        ?: runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        private set

    private val _challengeRequired = MutableStateFlow(false)

    /** true, gdy interceptor napotkał challenge i trzeba pokazać ekran WebView. */
    val challengeRequired: StateFlow<Boolean> = _challengeRequired.asStateFlow()

    // Na tym czeka zablokowane żądanie, aż użytkownik rozwiąże challenge w WebView. Współdzielone
    // przez wszystkie żądania, które trafiły na bramkę naraz: WebView otwiera się raz, a po
    // zdobyciu clearance wszystkie wstrzymane żądania budzą się i ponawiają. true = jest clearance.
    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Zgłasza wykryty challenge i zwraca sygnał, na którym [ChallengeInterceptor] czeka do czasu
     * rozwiązania. Współbieżne wywołania w trakcie jednego challengu dostają ten sam sygnał.
     */
    @Synchronized
    fun requireChallenge(): CompletableDeferred<Boolean> {
        val existing = pending
        val signal = if (existing != null && !existing.isCompleted) {
            existing
        } else {
            Log.d("CCI_CF", "challenge wykryty — otwieram WebView")
            CompletableDeferred<Boolean>().also { pending = it }
        }
        _challengeRequired.value = true
        return signal
    }

    /**
     * Gasi flagę bez przenoszenia cookies — gdy użytkownik zamknie ekran bez rozwiązania challengu.
     * Bez tego flaga zostałaby `true` i kolejne zablokowane żądanie nie wywołałoby już przejścia
     * `false → true`, na którym stoi otwieranie ekranu. Budzi też wstrzymane żądania (z wynikiem
     * „brak clearance"), żeby nie wisiały aż do timeoutu.
     */
    @Synchronized
    fun dismissChallenge() {
        _challengeRequired.value = false
        pending?.complete(false)
        pending = null
    }

    /**
     * Przenosi cookies rozwiązanego challengu z WebView do jara OkHttpa i zapamiętuje UA WebView.
     * Wołane z ekranu [WebView] po wykryciu `cf_clearance`. Zwraca true, jeśli było co przenieść.
     */
    @Synchronized
    fun syncFromWebView(webViewUserAgent: String): Boolean {
        userAgent = webViewUserAgent
        prefs.edit().putString("user_agent", webViewUserAgent).apply()

        val url = baseUrl.toHttpUrl()
        val rawCookies = CookieManager.getInstance().getCookie(baseUrl) ?: return false
        val appHasSession = cookieJar.loadForRequest(url).any { it.name == SESSION_COOKIE }
        val cookies = selectTransferable(parseWebViewCookies(rawCookies, url), appHasSession)
        if (cookies.isEmpty()) return false

        cookieJar.saveFromResponse(url, cookies)
        val hasClearance = cookies.any { it.name == CLEARANCE_COOKIE }
        Log.d("CCI_CF", "sync z WebView: ${cookies.size} cookies, cf_clearance=$hasClearance")
        if (hasClearance) {
            _challengeRequired.value = false
            // Budzi wstrzymane żądania — teraz jar ma cf_clearance, więc ponowienie przejdzie.
            pending?.complete(true)
            pending = null
        }
        return hasClearance
    }

    /**
     * Wybiera cookies, które wolno przenieść z WebView do jara OkHttpa.
     *
     * Cookies Cloudflare przenosimy zawsze — po to jest cały ten mechanizm. Cookies sesji serwisu
     * przenosimy **tylko wtedy, gdy aplikacja własnej sesji jeszcze nie ma**. W WebView użytkownik
     * jest zwykle gościem: otwiera się tam po to, żeby przejść challenge, nie żeby się zalogować.
     * Przeniesienie gościnnego `crowncapsinfo-session` razem z `XSRF-TOKEN` nadpisywało
     * uwierzytelnioną sesję OkHttpa, przez co `POST /data/…` (m.in. wyszukiwanie kapsla) wracał
     * z 403 — a `AuthRepository` wciąż widział cookie o właściwej nazwie i uznawał użytkownika za
     * zalogowanego, więc nie było ani ponowienia, ani wylogowania. Odzyskiwanie wygasłej sesji
     * należy do `ReauthInterceptor` (ciche logowanie), nie do tego ekranu.
     */
    fun selectTransferable(cookies: List<Cookie>, appHasSession: Boolean): List<Cookie> =
        if (appHasSession) cookies.filterNot { it.name in SESSION_COOKIES } else cookies

    private companion object {
        const val CLEARANCE_COOKIE = "cf_clearance"
        const val SESSION_COOKIE = "crowncapsinfo-session"

        // Cookies, którymi serwis identyfikuje sesję webową — nie wolno ich nadpisać gościnnymi
        // z WebView, gdy aplikacja ma już zalogowaną sesję. XSRF-TOKEN idzie w parze z sesją:
        // token z innej sesji dałby 419 przy pierwszym POST.
        val SESSION_COOKIES = setOf(SESSION_COOKIE, "XSRF-TOKEN")

        // Trzymamy przeniesione cookies jak najdłużej po stronie klienta. O realnym czasie życia
        // cf_clearance decyduje i tak Cloudflare (ustawienie „Challenge Passage" serwisu), a
        // dokładnego Max-Age nie da się odczytać przez CookieManager.getCookie. Dlatego zamiast
        // kasować cookie lokalnie po sztywnym czasie (wcześniej 20 min — wyrzucało wciąż ważne
        // clearance), trzymamy je bardzo długo i wysyłamy, dopóki serwer nie odrzuci. Gdy naprawdę
        // wygaśnie, przychodzi 403 z challengem, ChallengeInterceptor otwiera WebView, a nowe
        // cf_clearance nadpisuje to cookie. Długi czas życia = clearance przeżywa restart aplikacji.
        val EXPIRY_MS = 365L * 24 * 60 * 60 * 1000
    }

    /**
     * Rozbija łańcuch `nazwa=wartosc; nazwa2=wartosc2` z [CookieManager] na cookies OkHttpa dla
     * hosta [url]. Host-only i `secure`, bo serwis chodzi po HTTPS, a `cf_clearance` jest przypięty
     * do hosta. Wydzielone jako czysta funkcja, żeby dało się przetestować bez Androida.
     */
    fun parseWebViewCookies(
        rawCookies: String,
        url: HttpUrl,
        now: Long = System.currentTimeMillis(),
    ): List<Cookie> =
        rawCookies.split(';').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            Cookie.Builder()
                .name(name)
                .value(value)
                .hostOnlyDomain(url.host)
                .path("/")
                .secure()
                .expiresAt(now + EXPIRY_MS)
                .build()
        }
}

package pl.sroki.cci.android.data.datasource.remote.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.core.content.edit
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

/** Cookie, którym serwis identyfikuje sesję webową Sanctum. */
internal const val SESSION_COOKIE_NAME = "crowncapsinfo-session"

/**
 * Komplet cookies niosących tożsamość sesji webowej.
 *
 * Nigdy nie przenoszone z WebView ([ClearanceStore.selectTransferable]) i porzucane przed
 * każdym logowaniem ([pl.sroki.cci.android.data.datasource.remote.auth.WebSessionCookies]).
 * `XSRF-TOKEN` idzie w parze z sesją: token z innej sesji dałby 419 przy pierwszym POST.
 */
internal val SESSION_COOKIE_NAMES = setOf(SESSION_COOKIE_NAME, "XSRF-TOKEN")

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
 *  2. przenosi cookies Cloudflare z [CookieManager] WebView do [PersistentCookieJar] OkHttpa,
 *     żeby żądania API niosły `cf_clearance` — bez cookies sesji, patrz [selectTransferable];
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

    private val _challengeGeneration = MutableStateFlow(0)

    /**
     * Numer kolejnego challengu, rosnący przy każdym **nowym** (nie współbieżnym) wykryciu bramki.
     *
     * Samo [challengeRequired] nie wystarcza warstwie UI: Cloudflare wystawia challenge seriami,
     * a interceptor ponawia żądanie od razu po rozwiązaniu. Przejście `true → false → true` potrafi
     * zmieścić się w jednej klatce, a `StateFlow` konflatuje — obserwator widzi wtedy nieprzerwane
     * `true` i nie ma jak odróżnić drugiego challengu od pierwszego. Licznik zawsze dostaje nową
     * wartość, więc UI wie, że trzeba zacząć od nowa: przeładować stronę w WebView i odmierzyć próg
     * pokazania bramki od zera zamiast sumować całą serię.
     */
    val challengeGeneration: StateFlow<Int> = _challengeGeneration.asStateFlow()

    // Na tym czeka zablokowane żądanie, aż użytkownik rozwiąże challenge w WebView. Współdzielone
    // przez wszystkie żądania, które trafiły na bramkę naraz: WebView otwiera się raz, a po
    // zdobyciu clearance wszystkie wstrzymane żądania budzą się i ponawiają. true = jest clearance.
    private var pending: CompletableDeferred<Boolean>? = null

    // Wartość cf_clearance, którą bramka właśnie odrzuciła. Bez niej nie da się odróżnić
    // rozwiązanego challengu od cookie, które i tak już mieliśmy — patrz [isFreshClearance].
    private var rejectedClearance: String? = null

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
            // Nowy challenge: zapamiętaj clearance, z którym żądanie właśnie odbiło się od bramki.
            rejectedClearance = currentClearance()
            _challengeGeneration.value += 1
            Log.d("CCI_CF", "challenge wykryty (nr ${_challengeGeneration.value}) — WebView rozwiazuje w tle")
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
    fun dismissChallenge() = releaseChallenge()

    /**
     * Rezygnacja po stronie interceptora: oczekiwanie wygasło albo skończyły się próby.
     *
     * Bez tego mechanizm zostawał w stanie, z którego wychodził tylko restart aplikacji: flaga
     * [challengeRequired] zostawała `true`, więc nawigacja (reagująca na przejście `false → true`)
     * nie otwierała już WebView, a nieukończone [pending] było oddawane kolejnym żądaniom jako
     * ten sam martwy sygnał — czekały pełny timeout i padały.
     */
    @Synchronized
    fun abandonChallenge() {
        Log.d("CCI_CF", "rezygnacja z challengu — zeruje stan, kolejny znow otworzy WebView")
        releaseChallenge()
    }

    private fun releaseChallenge() {
        _challengeRequired.value = false
        pending?.complete(false)
        pending = null
    }

    private fun currentClearance(): String? =
        cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == CLEARANCE_COOKIE }
            ?.value

    /**
     * Przenosi cookies rozwiązanego challengu z WebView do jara OkHttpa i zapamiętuje UA WebView.
     * Wołane z ekranu [WebView] po wykryciu `cf_clearance`. Zwraca true, jeśli było co przenieść.
     */
    @Synchronized
    fun syncFromWebView(webViewUserAgent: String): Boolean {
        if (userAgent != webViewUserAgent) {
            userAgent = webViewUserAgent
            prefs.edit { putString("user_agent", webViewUserAgent) }
        }

        val url = baseUrl.toHttpUrl()
        val rawCookies = CookieManager.getInstance().getCookie(baseUrl) ?: return false
        val cookies = selectTransferable(parseWebViewCookies(rawCookies, url))
        if (cookies.isEmpty()) return false

        cookieJar.saveFromResponse(url, cookies)
        val fresh = isFreshClearance(cookies, rejectedClearance)
        Log.d("CCI_CF", "sync z WebView: ${cookies.size} cookies, swieze cf_clearance=$fresh")
        if (fresh) {
            _challengeRequired.value = false
            // Budzi wstrzymane żądania — teraz jar ma świeże cf_clearance, więc ponowienie przejdzie.
            pending?.complete(true)
            pending = null
        }
        return fresh
    }

    /**
     * Czy przeniesione cookies niosą `cf_clearance` **inne** niż to, które bramka odrzuciła.
     *
     * Sama obecność cookie nic nie znaczy: w `CookieManager` WebView zwykle siedzi to samo,
     * właśnie unieważnione clearance. Uznawanie challengu za rozwiązany po samej obecności
     * zamykało ekran już na stronie „Just a moment…", ponowienie szło z tym samym cookie i
     * wracało z kolejnym challengem — trzy takie cykle po ~0,7 s wyglądały jak migające
     * okienko zakończone błędem, bo użytkownik nie miał kiedy niczego kliknąć.
     */
    fun isFreshClearance(cookies: List<Cookie>, rejected: String?): Boolean {
        val clearance = cookies.firstOrNull { it.name == CLEARANCE_COOKIE }?.value
        return clearance != null && clearance != rejected
    }

    /**
     * Wybiera cookies, które wolno przenieść z WebView do jara OkHttpa: wszystko poza sesją.
     *
     * Cookies Cloudflare przenosimy zawsze — po to jest cały ten mechanizm. Cookies sesji serwisu
     * **nigdy**: w WebView użytkownik jest gościem. [ClearanceGate] otwiera go wyłącznie po to,
     * żeby wykonać JavaScript challengu, i nie ma w nim żadnej ścieżki logowania — poprzedni
     * `ClearanceScreen`, w którym użytkownik mógł się zalogować na stronie, zniknął w `c9a8ce5`.
     *
     * Przeniesienie gościnnego `crowncapsinfo-session` razem z `XSRF-TOKEN` kosztowało dwa błędy.
     * Najpierw (`c17aff3`) nadpisywało uwierzytelnioną sesję OkHttpa: `POST /data/…` wracał z 403,
     * a `AuthRepository` wciąż widział cookie o właściwej nazwie i uznawał użytkownika za
     * zalogowanego, więc nie było ani ponowienia, ani wylogowania. Warunkowa ochrona z tamtego
     * commita („nie nadpisuj, gdy aplikacja ma już sesję") nie działała w jedynym przypadku,
     * który miał znaczenie — gdy sesja i `cf_clearance` wygasną naraz, chronić nie ma czego,
     * i gościnne cookies wjeżdżały do jara obok tych z logowania (`f963c6f`).
     *
     * Odzyskiwanie wygasłej sesji należy do `ReauthInterceptor` (ciche logowanie), nie do WebView.
     */
    fun selectTransferable(cookies: List<Cookie>): List<Cookie> =
        cookies.filterNot { it.name in SESSION_COOKIE_NAMES }

    private companion object {
        const val CLEARANCE_COOKIE = "cf_clearance"

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

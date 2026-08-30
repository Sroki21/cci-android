package pl.sroki.cci.android.data.datasource.remote.auth

import android.util.Log
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cookies sesji webowej w jarze OkHttpa: porzucanie ich i wykrywanie, że jar jest zatruty.
 *
 * Powód istnienia: `PersistentCookieJar` rozróżnia wpisy po **pełnej tożsamości** cookie
 * (domena, ścieżka, flagi), a nie po samej nazwie. Cookie sesji zbudowane przez nas z
 * `CookieManager` WebView (host-only + secure) i to samo cookie przysłane przez serwer
 * z własnymi atrybutami to dla jara **dwa osobne wpisy — i oba lecą w nagłówku `Cookie`**.
 * Serwer bierze wtedy gościnny i odmawia, a stan żyje w pamięci procesu, więc przeżywa
 * kolejne logowania: naprawiał dopiero restart aplikacji. Pełny opis w commicie `f963c6f`.
 *
 * Wydzielone z `SessionRefresher`, bo tego samego potrzebują trzy miejsca ścieżki
 * uwierzytelniania: ciche logowanie, logowanie ręczne i decyzja o pominięciu okna świeżości.
 */
@Singleton
class WebSessionCookies @Inject constructor(
    private val cookieJar: PersistentCookieJar,
    private val baseUrl: String,
) {

    /**
     * Czy w jarze siedzi więcej niż jeden wpis cookie sesji — czyli czy jar jest zatruty.
     *
     * `loadForRequest` zwraca dokładnie to, co poleci w nagłówku `Cookie`, więc duplikat widać
     * tu wprost. Sam `CookiePersistence.xml` go nie pokazuje: kluczem jest tam `url|nazwa`,
     * więc oba warianty sklejają się w jeden wpis na dysku.
     */
    fun isDuplicated(): Boolean =
        cookieJar.loadForRequest(baseUrl.toHttpUrl()).count { it.name == SESSION_COOKIE_NAME } > 1

    /**
     * Porzuca wszystkie cookies sesji, żeby logowanie zaczynało od czystej karty. Zwraca ile
     * wpisów porzucono — liczba większa od liczby nazw w [SESSION_COOKIE_NAMES] jest dowodem,
     * że duplikat faktycznie istniał.
     *
     * `PersistentCookieJar` nie ma API do skasowania pojedynczego cookie, więc usuwamy przez
     * nadpisanie wygasłą kopią: `loadForRequest` wymiata przeterminowane z cache **i** z dysku.
     * Kopia zachowuje domenę, ścieżkę i flagi, więc trafia dokładnie w ten sam wpis — czyścimy
     * każdy wariant, nie zgadując, który z nich jest tym zatrutym.
     *
     * `cf_clearance` zostaje nietknięty: jego skasowanie kosztowałoby kolejny challenge.
     */
    fun drop(): Int {
        val url = baseUrl.toHttpUrl()
        val stale = cookieJar.loadForRequest(url).filter { it.name in SESSION_COOKIE_NAMES }
        if (stale.isEmpty()) return 0
        cookieJar.saveFromResponse(url, stale.map { it.expired() })
        cookieJar.loadForRequest(url)
        Log.d("CCI_AUTH", "porzucono ${stale.size} cookies starej sesji przed logowaniem")
        return stale.size
    }

    /** Ta sama tożsamość cookie (domena/ścieżka/flagi), ale z datą wygaśnięcia w przeszłości. */
    private fun Cookie.expired(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .path(path)
        .expiresAt(1L)
        .let { if (hostOnly) it.hostOnlyDomain(domain) else it.domain(domain) }
        .let { if (secure) it.secure() else it }
        .let { if (httpOnly) it.httpOnly() else it }
        .build()
}

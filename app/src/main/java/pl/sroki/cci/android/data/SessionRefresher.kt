package pl.sroki.cci.android.data

import android.util.Log
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import pl.sroki.cci.android.data.datasource.local.CredentialsStore
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.data.datasource.remote.auth.SESSION_COOKIE_NAMES
import pl.sroki.cci.android.model.LoginRequest
import javax.inject.Inject
import javax.inject.Singleton

enum class ReauthResult {
    /** Sesja odnowiona — request można ponowić. */
    SUCCESS,

    /** Brak zapisanych poświadczeń (np. logowanie sprzed tej wersji) — nie da się odzyskać. */
    NO_CREDENTIALS,

    /** Backend odrzucił poświadczenia — hasło zmienione. Trzeba zalogować się ręcznie. */
    REJECTED,

    /** Sieć/serwer niedostępny. Sesja może być wciąż dobra — NIE wylogowujemy. */
    UNAVAILABLE
}

/**
 * Odnawia uwierzytelnienie bez udziału użytkownika. Wołany z ReauthInterceptor po 401/419.
 *
 * Świadomie nie korzysta z AuthRepository.login() — tamten wariant ciągnie za sobą Firebase
 * i przywracanie kolekcji z Firestore, czego przy cichym odświeżeniu sesji nie chcemy.
 */
@Singleton
class SessionRefresher @Inject constructor(
    private val authApiService: AuthApiService,
    private val credentialsStore: CredentialsStore,
    private val sessionRepository: SessionRepository,
    private val cookieJar: PersistentCookieJar,
    private val baseUrl: String
) {

    private companion object {
        /**
         * Gdy kilka żądań dostanie 401 równocześnie, przez mutex przechodzą po kolei. Odnowienie
         * sprzed chwili obsługuje je wszystkie, więc kolejne w kolejce nie logują się ponownie.
         */
        const val FRESH_WINDOW_MS = 10_000L
    }

    private val mutex = Mutex()
    private var lastSuccessAt = 0L
    private var lastCsrfAt = 0L

    /** Tani wariant: sesja może wciąż żyć, a rozjechał się tylko token CSRF. */
    suspend fun refreshCsrf(): Boolean = mutex.withLock {
        // To samo okno co przy pełnym logowaniu: dziesięć równoległych 401 nie ma powodu
        // wywoływać dziesięciu razy sanctum/csrf-cookie — pierwszy odświeża dla wszystkich.
        if (System.currentTimeMillis() - lastCsrfAt < FRESH_WINDOW_MS) return true
        runCatching { authApiService.initCsrf().isSuccessful }
            .onSuccess { if (it) lastCsrfAt = System.currentTimeMillis() }
            .onFailure { Log.w("CCI_AUTH", "reauth: odświeżenie CSRF nieudane: ${it.message}") }
            .getOrDefault(false)
    }

    /** Pełny wariant: ciche zalogowanie zapisanymi poświadczeniami. */
    suspend fun reauthenticate(): ReauthResult = mutex.withLock {
        if (System.currentTimeMillis() - lastSuccessAt < FRESH_WINDOW_MS) return ReauthResult.SUCCESS

        val credentials = credentialsStore.load() ?: return ReauthResult.NO_CREDENTIALS
        val response = runCatching {
            dropSessionCookies()
            authApiService.initCsrf()
            authApiService.login(LoginRequest(credentials.email, credentials.password))
        }.getOrElse {
            Log.w("CCI_AUTH", "reauth: logowanie nieosiągalne: ${it.message}")
            return ReauthResult.UNAVAILABLE
        }

        Log.d("CCI_AUTH", "reauth: ciche logowanie code=${response.code()}")
        return when (response.code()) {
            // 302 to sukces — backend przekierowuje po zalogowaniu webowym, a CookieJar
            // zapisał już świeżą sesję z tej odpowiedzi.
            200, 302 -> {
                lastSuccessAt = System.currentTimeMillis()
                sessionRepository.setLoggedIn(true)
                ReauthResult.SUCCESS
            }
            401, 403, 419, 422 -> ReauthResult.REJECTED
            else -> ReauthResult.UNAVAILABLE
        }
    }

    /** Odzyskanie Bearer tokenu dla `api/v1` — token wydawany jest na te same poświadczenia. */
    suspend fun refreshApiToken(): ReauthResult = mutex.withLock {
        val credentials = credentialsStore.load() ?: return ReauthResult.NO_CREDENTIALS
        return runCatching {
            val stored = sessionRepository.fetchAndStoreApiToken(
                credentials.email,
                credentials.password,
                force = true
            )
            if (stored) ReauthResult.SUCCESS else ReauthResult.REJECTED
        }.getOrElse {
            Log.w("CCI_AUTH", "reauth: odświeżenie tokenu nieudane: ${it.message}")
            ReauthResult.UNAVAILABLE
        }
    }

    /** Poświadczenia są nieaktualne — kolejne ciche próby nie mają sensu. */
    fun forgetCredentials() {
        credentialsStore.clear()
    }

    /**
     * Porzuca cookies starej sesji, żeby logowanie zaczynało od czystej karty.
     *
     * Bez tego ciche logowanie potrafiło zwrócić 200, a mimo to **ponowione żądanie dostawało
     * 401** — i tak w kółko, aż do restartu procesu. Odtworzone na urządzeniu 2026-08-18:
     * gdy sesja webowa i `cf_clearance` wygasną naraz (kilka dni bez otwierania aplikacji),
     * `ClearanceStore.selectTransferable` nie ma czego chronić (`appHasSession=false`) i wpuszcza
     * do jara **gościnne** `crowncapsinfo-session` oraz `XSRF-TOKEN` z WebView. Cookie z WebView
     * budujemy sami (host-only + secure), a to od serwera przychodzi z własnymi atrybutami —
     * jeśli się różnią, `PersistentCookieJar` widzi dwa osobne wpisy i wysyła oba. Serwer bierze
     * gościnny i odmawia. Zły stan siedzi w pamięci jara, dlatego przeżywał kolejne logowania,
     * a `CookiePersistence.xml` nawet nie pokazywał duplikatu (klucz to `url|nazwa`).
     *
     * Usuwamy przez nadpisanie wygasłą kopią: `PersistentCookieJar` nie ma API do skasowania
     * pojedynczego cookie, ale `loadForRequest` wymiata przeterminowane z cache **i** z dysku.
     * Kopia zachowuje domenę, ścieżkę i flagi, więc trafia dokładnie w ten sam wpis — czyścimy
     * każdy wariant, nie zgadując, który z nich jest tym zatrutym.
     */
    private fun dropSessionCookies() {
        val url = baseUrl.toHttpUrl()
        val stale = cookieJar.loadForRequest(url).filter { it.name in SESSION_COOKIE_NAMES }
        if (stale.isEmpty()) return
        cookieJar.saveFromResponse(url, stale.map { it.expired() })
        cookieJar.loadForRequest(url)
        Log.d("CCI_AUTH", "reauth: porzucono ${stale.size} cookies starej sesji przed logowaniem")
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

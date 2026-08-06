package pl.sroki.cci.android.data.datasource.remote.auth

import android.util.Log
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import pl.sroki.cci.android.data.ReauthResult
import pl.sroki.cci.android.data.SessionRefresher
import pl.sroki.cci.android.data.SessionRepository

/**
 * Odzyskuje uwierzytelnienie w locie, żeby wygaśnięcie sesji nie było widoczne dla użytkownika.
 *
 * Aplikacja uwierzytelnia się dwutorowo i oba tory psują się inaczej:
 *  - ścieżki `data/` (m.in. zmiana statusu kapsla) — sesja webowa Sanctum w cookie. Wygasa po
 *    kilkudziesięciu minutach; backend zwraca wtedy 401, a przy samej rotacji tokenu CSRF 419.
 *  - ścieżki `api/v1` (katalog) — Bearer token. Ginie znacznie rzadziej, 401.
 *
 * Musi to być interceptor aplikacyjny wpięty jako PIERWSZY, a nie OkHttp `Authenticator`:
 * ponowienie przez `chain.proceed()` przechodzi wtedy jeszcze raz przez BearerTokenInterceptor
 * i CsrfInterceptor, więc łapie świeże cookie i świeży token CSRF. Request zwrócony przez
 * `Authenticator` omija interceptory aplikacyjne i poszedłby ze starym nagłówkiem CSRF —
 * a na 419 `Authenticator` nie jest w ogóle wołany, bo OkHttp odpala go tylko na 401/407.
 */
class ReauthInterceptor(
    // Lazy: ReauthInterceptor ← provideOkHttpClient, a SessionRefresher ciągnie AuthApiService
    // z osobnego klienta @Named("auth") — Lazy trzyma graf Hilta rozpięty przy starcie.
    private val sessionRefresher: Lazy<SessionRefresher>,
    private val sessionRepository: SessionRepository
) : Interceptor {

    private companion object {
        const val RETRY_MARKER = "X-CCI-Reauth"
        val RECOVERABLE_CODES = setOf(401, 419)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.header(RETRY_MARKER) != null) return response

        val path = request.url.encodedPath
        return when {
            path.startsWith("/data/") && response.isRecoverableWebSession() ->
                recoverWebSession(chain, request, response)
            path.startsWith("/api/") && response.code in RECOVERABLE_CODES ->
                recoverApiToken(chain, request, response)
            else -> response
        }
    }

    /**
     * 401 i 419 to zwykłe wygaśnięcie sesji i rotacja tokenu CSRF. 403 dokładamy, bo serwis
     * odpowiada nim, gdy sesja przestała być uwierzytelniona i żądanie wygląda dla niego jak
     * żądanie gościa — takie 403 szło wcześniej prosto do UI zamiast uruchomić odnowienie sesji.
     * Bramka Cloudflare ma ten sam kod, więc ją wykluczamy: obsługuje ją [ChallengeInterceptor],
     * który stoi niżej w łańcuchu, a ciche logowanie i tak by się o nią rozbiło.
     */
    private fun Response.isRecoverableWebSession(): Boolean =
        code in RECOVERABLE_CODES || (code == 403 && !isCloudflareChallenge())

    private fun recoverWebSession(
        chain: Interceptor.Chain,
        request: Request,
        failed: Response
    ): Response {
        val refresher = sessionRefresher.get()

        // Krok 1 (tani): sesja może wciąż żyć, a rozjechał się tylko token CSRF.
        if (runBlocking { refresher.refreshCsrf() }) {
            val retried = chain.proceed(markRetry(request))
            if (retried.code !in RECOVERABLE_CODES) {
                Log.d("CCI_AUTH", "reauth: uratowane samym CSRF, code=${retried.code}")
                failed.close()
                return retried
            }
            retried.close()
        }

        // Krok 2 (pełny): sesja wygasła — ciche logowanie zapisanymi poświadczeniami.
        return retryAfter(chain, request, failed, runBlocking { refresher.reauthenticate() })
    }

    private fun recoverApiToken(
        chain: Interceptor.Chain,
        request: Request,
        failed: Response
    ): Response {
        val result = runBlocking { sessionRefresher.get().refreshApiToken() }
        // Bearer token jest odrzucony bezspornie — tu, w odróżnieniu od sesji webowej,
        // brak możliwości odzyskania oznacza realny koniec sesji.
        if (result == ReauthResult.NO_CREDENTIALS) sessionRepository.setLoggedIn(false)
        return retryAfter(chain, request, failed, result)
    }

    private fun retryAfter(
        chain: Interceptor.Chain,
        request: Request,
        failed: Response,
        result: ReauthResult
    ): Response {
        Log.d("CCI_AUTH", "reauth: ${request.url.encodedPath} -> $result")
        return when (result) {
            ReauthResult.SUCCESS -> {
                val retried = chain.proceed(markRetry(request))
                failed.close()
                retried
            }
            ReauthResult.REJECTED -> {
                // Hasło zmienione po stronie serwisu — dalsze ciche próby tylko by je powtarzały.
                sessionRefresher.get().forgetCredentials()
                sessionRepository.setToken(null)
                sessionRepository.setLoggedIn(false)
                failed
            }
            // UNAVAILABLE: sieć padła w trakcie odnawiania — sesja może być wciąż dobra,
            // więc nie wylogowujemy. NO_CREDENTIALS: nie mamy czym się zalogować.
            // W obu wypadkach oryginalny błąd leci do UI.
            ReauthResult.UNAVAILABLE, ReauthResult.NO_CREDENTIALS -> failed
        }
    }

    private fun markRetry(request: Request): Request =
        request.newBuilder().header(RETRY_MARKER, "1").build()
}

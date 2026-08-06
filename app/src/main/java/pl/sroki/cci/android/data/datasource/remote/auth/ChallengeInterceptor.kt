package pl.sroki.cci.android.data.datasource.remote.auth

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Wykrywa bramkę Cloudflare, otwiera ekran WebView i po zdobyciu clearance ponawia żądanie.
 *
 * Sygnał rozpoznawczy to nagłówek `Cf-Mitigated: challenge`, którym Cloudflare oznacza odpowiedź
 * z interstitialem „Just a moment…". Interceptor zapala flagę w [ClearanceStore] (warstwa
 * nawigacji otwiera na nią WebView) i **blokuje wątek sieciowy** aż użytkownik ręcznie przejdzie
 * challenge, dokładnie jak [ReauthInterceptor] blokuje przy odnawianiu sesji. Gdy `cf_clearance`
 * trafi do jara, żądanie leci jeszcze raz — tym razem z clearance i właściwym UA — i przechodzi.
 *
 * Bez tego oczekiwania pierwsze żądanie kończyło się widocznym 403: challenge rozwiązywał się
 * dopiero po jego porażce, więc użytkownik musiał ponawiać akcję ręcznie.
 */
class ChallengeInterceptor(private val clearanceStore: ClearanceStore) : Interceptor {

    private companion object {
        // Ile czekamy na jedno ręczne rozwiązanie challengu, zanim oddamy 403.
        const val WAIT_MS = 120_000L
        // Ile razy ponawiamy. Świeżo zdobyty cf_clearance bywa od razu ponownie oflagowany
        // (Cloudflare potrafi wystawić kolejny interstitial), więc jedno ponowienie to za mało —
        // trzeba doczekać kilku rozwiązań. Kilka prób pod rząd, potem oddajemy 403.
        const val MAX_ATTEMPTS = 3
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempts = 0
        while (response.isChallenge() && attempts < MAX_ATTEMPTS) {
            val signal = clearanceStore.requireChallenge()
            val cleared = runBlocking { withTimeoutOrNull(WAIT_MS) { signal.await() } } ?: false
            Log.d("CCI_CF", "clearance dla ${chain.request().url.encodedPath}: proba ${attempts + 1}, cleared=$cleared")
            // Użytkownik zamknął challenge bez rozwiązania — nie ma sensu ponawiać.
            if (!cleared) break
            response.close()
            attempts++
            response = chain.proceed(chain.request())
        }
        return response
    }

    private fun Response.isChallenge(): Boolean =
        header("cf-mitigated").equals("challenge", ignoreCase = true)
}

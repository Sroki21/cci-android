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
        const val RETRY_MARKER = "X-CCI-Clearance"
        // Ile czekamy na ręczne rozwiązanie challengu, zanim oddamy pierwotny 403.
        const val WAIT_MS = 120_000L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isChallenge()) return response
        // Po jednym ponowieniu odpuszczamy — inaczej uparta bramka zapętliłaby żądanie.
        if (chain.request().header(RETRY_MARKER) != null) return response

        val signal = clearanceStore.requireChallenge()
        val cleared = runBlocking { withTimeoutOrNull(WAIT_MS) { signal.await() } } ?: false
        Log.d("CCI_CF", "czekanie na clearance dla ${chain.request().url.encodedPath}: cleared=$cleared")
        if (!cleared) return response

        response.close()
        val retried = chain.request().newBuilder().header(RETRY_MARKER, "1").build()
        return chain.proceed(retried)
    }

    private fun Response.isChallenge(): Boolean =
        header("cf-mitigated").equals("challenge", ignoreCase = true)
}

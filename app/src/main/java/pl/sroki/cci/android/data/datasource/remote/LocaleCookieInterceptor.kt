package pl.sroki.cci.android.data.datasource.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Wymusza polską wersję językową odpowiedzi serwisu przez cookie `user-locale`.
 *
 * Serwis dobiera język nazw (m.in. krajów) po tym cookie, a nie po nagłówku `Accept-Language`.
 * Musi to być **network** interceptor: nagłówek `Cookie` składa dopiero `CookieJar`, więc na
 * poziomie aplikacyjnym nie byłoby czego podmieniać.
 */
class LocaleCookieInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = withPolishLocale(request.header(COOKIE_HEADER).orEmpty())
        return chain.proceed(request.newBuilder().header(COOKIE_HEADER, cookies).build())
    }

    /** Podmienia istniejące `user-locale` albo dokłada je do łańcucha cookies. */
    fun withPolishLocale(cookies: String): String = when {
        LOCALE_COOKIE in cookies -> cookies.replace(LOCALE_PAIR, POLISH)
        cookies.isEmpty() -> POLISH
        else -> "$cookies; $POLISH"
    }

    private companion object {
        const val COOKIE_HEADER = "Cookie"
        const val LOCALE_COOKIE = "user-locale="
        const val POLISH = "user-locale=pl"
        val LOCALE_PAIR = Regex("user-locale=[^;\\s]*")
    }
}

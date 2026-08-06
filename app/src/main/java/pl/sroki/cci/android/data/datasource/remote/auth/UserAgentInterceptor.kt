package pl.sroki.cci.android.data.datasource.remote.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Nadaje każdemu żądaniu User-Agent zgodny z tym, którym WebView rozwiązał challenge Cloudflare.
 *
 * `cf_clearance` jest ważny wyłącznie dla pary (User-Agent, IP), która go uzyskała. Bez tego
 * przeniesione cookie zostałoby odrzucone i challenge wracałby w kółko. UA pochodzi z
 * [ClearanceStore]; dopóki go nie ma, zostaje domyślny UA OkHttpa.
 */
class UserAgentInterceptor(private val clearanceStore: ClearanceStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val ua = clearanceStore.userAgent ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("User-Agent", ua)
            .build()
        return chain.proceed(request)
    }
}

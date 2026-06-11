package pl.sroki.cci.android.data.datasource.remote.auth

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLDecoder

class CsrfInterceptor(private val cookieJar: CookieJar) : Interceptor {

    private val mutatingMethods = setOf("POST", "PUT", "DELETE", "PATCH")

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method !in mutatingMethods) return chain.proceed(original)

        val token = cookieJar.loadForRequest(original.url)
            .firstOrNull { it.name == "XSRF-TOKEN" }
            ?.value
            ?: return chain.proceed(original)

        val decoded = URLDecoder.decode(token, "UTF-8")
        val request = original.newBuilder()
            .header("X-XSRF-TOKEN", decoded)
            .build()
        return chain.proceed(request)
    }
}

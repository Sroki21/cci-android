package pl.sroki.cci.android.data.datasource.remote.auth

import okhttp3.Interceptor
import okhttp3.Response

class AcceptJsonInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("Accept-Language", "pl")
            .header("Origin", "http://localhost:3000")
            .header("Referer", "http://localhost:3000")
            .build()
        return chain.proceed(request)
    }
}

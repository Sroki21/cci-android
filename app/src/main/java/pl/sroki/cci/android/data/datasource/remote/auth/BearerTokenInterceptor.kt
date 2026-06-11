package pl.sroki.cci.android.data.datasource.remote.auth

import okhttp3.Interceptor
import okhttp3.Response
import pl.sroki.cci.android.data.SessionRepository

class BearerTokenInterceptor(private val sessionRepository: SessionRepository) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = sessionRepository.token.value
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

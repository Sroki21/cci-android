package pl.sroki.cci.android.data.datasource.remote.auth

import okhttp3.ResponseBody
import pl.sroki.cci.android.model.LoginRequest
import pl.sroki.cci.android.model.TokenRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApiService {

    @GET("sanctum/csrf-cookie")
    suspend fun initCsrf(): Response<Unit>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<ResponseBody>

    @POST("api/v1/token")
    suspend fun apiToken(@Body body: TokenRequest): Response<ResponseBody>

    @POST("logout")
    suspend fun logout(): Response<Unit>
}

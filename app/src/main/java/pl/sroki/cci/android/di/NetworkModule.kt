package pl.sroki.cci.android.di

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pl.sroki.cci.android.BuildConfig
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.datasource.remote.CapApiService
import pl.sroki.cci.android.data.datasource.remote.CategoryApiService
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import pl.sroki.cci.android.data.datasource.remote.auth.AcceptJsonInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.data.datasource.remote.auth.BearerTokenInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.CsrfInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.SessionAuthenticator
import retrofit2.Converter
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Singleton
    @Provides
    fun provideBaseURL(): String {
        return "https://crowncaps.info"
    }

    @Singleton
    @Provides
    fun provideConverterFactory(): Converter.Factory {
        val json = Json { ignoreUnknownKeys = true }
        return json.asConverterFactory("application/json".toMediaType())
    }

    @Singleton
    @Provides
    fun provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar {
        return PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))
    }

    @Singleton
    @Provides
    fun provideSessionAuthenticator(
        cookieJar: PersistentCookieJar,
        sessionRepository: SessionRepository
    ): Authenticator {
        return SessionAuthenticator(cookieJar, sessionRepository)
    }

    @Singleton
    @Provides
    fun provideOkHttpClient(
        cookieJar: PersistentCookieJar,
        authenticator: Authenticator,
        sessionRepository: SessionRepository
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(AcceptJsonInterceptor())
            .addInterceptor(BearerTokenInterceptor(sessionRepository))
            .addInterceptor(CsrfInterceptor(cookieJar))
            .authenticator(authenticator)
        builder.addNetworkInterceptor { chain ->
            val req = chain.request()
            val cookies = req.header("Cookie") ?: ""
            val fixed = if ("user-locale=" in cookies)
                cookies.replace(Regex("user-locale=[^;\\s]*"), "user-locale=pl")
            else
                if (cookies.isEmpty()) "user-locale=pl" else "$cookies; user-locale=pl"
            chain.proceed(req.newBuilder().header("Cookie", fixed).build())
        }
        if (BuildConfig.DEBUG) {
            builder.addNetworkInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }
        return builder.build()
    }

    @Singleton
    @Provides
    fun provideRetrofitClient(
        baseUrl: String,
        converterFactory: Converter.Factory,
        okHttpClient: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(converterFactory)
            .client(okHttpClient)
            .build()
    }

    @Singleton
    @Provides
    fun provideCountriesApiService(retrofit: Retrofit): CountryApiService {
        return retrofit.create(CountryApiService::class.java)
    }

    @Singleton
    @Provides
    fun provideCategoriesApiService(retrofit: Retrofit): CategoryApiService {
        return retrofit.create(CategoryApiService::class.java)
    }

    @Singleton
    @Provides
    fun provideCapApiService(retrofit: Retrofit): CapApiService {
        return retrofit.create(CapApiService::class.java)
    }

    // Osobny klient dla endpointów auth — nie śledzi redirectów (302 po POST /auth/login
    // to sukces; śledzenie redirectu do GET / nadpisywało uwierzytelnioną sesję gościnną).
    @Singleton
    @Provides
    @Named("auth")
    fun provideAuthOkHttpClient(
        cookieJar: PersistentCookieJar,
        sessionRepository: SessionRepository
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(AcceptJsonInterceptor())
            .addInterceptor(BearerTokenInterceptor(sessionRepository))
            .addInterceptor(CsrfInterceptor(cookieJar))
            .followRedirects(false)
        if (BuildConfig.DEBUG) {
            builder.addNetworkInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }
        return builder.build()
    }

    @Singleton
    @Provides
    @Named("auth")
    fun provideAuthRetrofit(
        baseUrl: String,
        converterFactory: Converter.Factory,
        @Named("auth") authOkHttpClient: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(converterFactory)
            .client(authOkHttpClient)
            .build()
    }

    @Singleton
    @Provides
    fun provideAuthApiService(@Named("auth") retrofit: Retrofit): AuthApiService {
        return retrofit.create(AuthApiService::class.java)
    }
}

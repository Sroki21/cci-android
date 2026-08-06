package pl.sroki.cci.android.di

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pl.sroki.cci.android.BuildConfig
import pl.sroki.cci.android.data.AppJson
import pl.sroki.cci.android.data.SessionRefresher
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.datasource.remote.CapApiService
import pl.sroki.cci.android.data.datasource.remote.CategoryApiService
import pl.sroki.cci.android.data.datasource.remote.CountryApiService
import pl.sroki.cci.android.data.datasource.remote.LocaleCookieInterceptor
import pl.sroki.cci.android.data.datasource.remote.ProducerApiService
import pl.sroki.cci.android.data.datasource.remote.ProductFilterInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.AcceptJsonInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.data.datasource.remote.auth.BearerTokenInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.ChallengeInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.ClearanceStore
import pl.sroki.cci.android.data.datasource.remote.auth.CsrfInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.ReauthInterceptor
import pl.sroki.cci.android.data.datasource.remote.auth.UserAgentInterceptor
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
        return AppJson.asConverterFactory("application/json".toMediaType())
    }

    @Singleton
    @Provides
    fun provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar {
        return PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))
    }

    /**
     * Wspólny fundament obu klientów: jedna pula połączeń, jeden dispatcher, jeden jar cookies.
     * Klienty wyprowadzają się z niego przez `newBuilder()` i dokładają własne interceptory —
     * budowane osobno trzymałyby po własnym zestawie gniazd i wątków do tego samego hosta.
     */
    @Singleton
    @Provides
    @Named("base")
    fun provideBaseOkHttpClient(cookieJar: PersistentCookieJar): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()
    }

    @Singleton
    @Provides
    fun provideOkHttpClient(
        @Named("base") baseClient: OkHttpClient,
        cookieJar: PersistentCookieJar,
        sessionRepository: SessionRepository,
        sessionRefresher: Lazy<SessionRefresher>,
        clearanceStore: ClearanceStore
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
            // MUSI być pierwszy: jego ponowienie przechodzi jeszcze raz przez interceptory
            // poniżej, więc dostaje świeże cookie sesji, świeży CSRF i świeży Bearer token.
            .addInterceptor(ReauthInterceptor(sessionRefresher, sessionRepository))
            // Wykrywa bramkę Cloudflare (403 Cf-Mitigated: challenge) i sygnalizuje UI.
            .addInterceptor(ChallengeInterceptor(clearanceStore))
            // UA zgodny z WebView — warunek ważności cf_clearance przeniesionego z przeglądarki.
            .addInterceptor(UserAgentInterceptor(clearanceStore))
            .addInterceptor(AcceptJsonInterceptor())
            .addInterceptor(BearerTokenInterceptor(sessionRepository))
            .addInterceptor(CsrfInterceptor(cookieJar))
            .addInterceptor(ProductFilterInterceptor())
            .addNetworkInterceptor(LocaleCookieInterceptor())
        if (BuildConfig.DEBUG) {
            builder.addNetworkInterceptor(loggingInterceptor(HttpLoggingInterceptor.Level.BODY))
        }
        return builder.build()
    }

    /**
     * Log sieciowy bez sekretów. Nagłówki `Authorization`, `Cookie` i `Set-Cookie` niosą Bearer
     * token oraz cookie sesji — komu wpadną w ręce, ten wchodzi na konto. Ciała redaguje się
     * doborem poziomu: katalog może iść na `BODY`, klient auth nie, bo wysyła hasło.
     */
    private fun loggingInterceptor(level: HttpLoggingInterceptor.Level) =
        HttpLoggingInterceptor().apply {
            this.level = level
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
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

    @Singleton
    @Provides
    fun provideProducerApiService(retrofit: Retrofit): ProducerApiService {
        return retrofit.create(ProducerApiService::class.java)
    }

    // Osobny klient dla endpointów auth — nie śledzi redirectów (302 po POST /auth/login
    // to sukces; śledzenie redirectu do GET / nadpisywało uwierzytelnioną sesję gościnną).
    @Singleton
    @Provides
    @Named("auth")
    fun provideAuthOkHttpClient(
        @Named("base") baseClient: OkHttpClient,
        cookieJar: PersistentCookieJar,
        sessionRepository: SessionRepository,
        clearanceStore: ClearanceStore
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
            // Logowanie też dostaje bramkę Cloudflare — te same dwa interceptory co w kliencie głównym.
            .addInterceptor(ChallengeInterceptor(clearanceStore))
            .addInterceptor(UserAgentInterceptor(clearanceStore))
            .addInterceptor(AcceptJsonInterceptor())
            .addInterceptor(BearerTokenInterceptor(sessionRepository))
            .addInterceptor(CsrfInterceptor(cookieJar))
            .followRedirects(false)
        if (BuildConfig.DEBUG) {
            // HEADERS, nie BODY: ciałem żądania jest tu LoginRequest z hasłem użytkownika.
            builder.addNetworkInterceptor(loggingInterceptor(HttpLoggingInterceptor.Level.HEADERS))
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

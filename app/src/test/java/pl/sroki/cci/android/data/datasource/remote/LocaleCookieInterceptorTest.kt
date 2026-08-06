package pl.sroki.cci.android.data.datasource.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleCookieInterceptorTest {

    private val interceptor = LocaleCookieInterceptor()

    @Test
    fun `brak cookies daje samo user-locale`() {
        assertEquals("user-locale=pl", interceptor.withPolishLocale(""))
    }

    @Test
    fun `dokleja user-locale do istniejacych cookies`() {
        val cookies = interceptor.withPolishLocale("crowncapsinfo-session=abc; XSRF-TOKEN=tok")

        assertEquals("crowncapsinfo-session=abc; XSRF-TOKEN=tok; user-locale=pl", cookies)
    }

    @Test
    fun `podmienia obcy jezyk na polski`() {
        assertEquals("user-locale=pl", interceptor.withPolishLocale("user-locale=en"))
    }

    @Test
    fun `podmiana w srodku lancucha nie rusza sasiadow`() {
        val cookies = interceptor.withPolishLocale("a=1; user-locale=de; b=2")

        assertEquals("a=1; user-locale=pl; b=2", cookies)
    }

    @Test
    fun `puste user-locale tez dostaje wartosc`() {
        assertEquals("a=1; user-locale=pl", interceptor.withPolishLocale("a=1; user-locale="))
    }
}

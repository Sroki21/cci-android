package pl.sroki.cci.android.data.datasource.remote.auth

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.ReauthResult
import pl.sroki.cci.android.data.SessionRefresher
import pl.sroki.cci.android.data.SessionRepository
import java.util.concurrent.TimeUnit

class ReauthInterceptorTest {

    private lateinit var sessionRefresher: SessionRefresher
    private lateinit var sessionRepository: SessionRepository
    private lateinit var interceptor: ReauthInterceptor

    private companion object {
        const val WEB_URL = "https://crowncaps.info/data/catalog/caps/1/collection"
        const val API_URL = "https://crowncaps.info/api/v1/caps/1"
    }

    @Before
    fun setUp() {
        sessionRefresher = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        interceptor = ReauthInterceptor(dagger.Lazy { sessionRefresher }, sessionRepository)
    }

    private fun chain(url: String, vararg codes: Int) =
        FakeChain(Request.Builder().url(url).build(), codes.toMutableList())

    @Test
    fun `odpowiedz sukcesem przechodzi bez odswiezania sesji`() {
        val chain = chain(WEB_URL, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(1, chain.proceeded.size)
    }

    @Test
    fun `419 na data — samo odswiezenie CSRF ratuje request`() {
        coEvery { sessionRefresher.refreshCsrf() } returns true
        val chain = chain(WEB_URL, 419, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, chain.proceeded.size)
        assertNotNull(chain.proceeded[1].header("X-CCI-Reauth"))
    }

    @Test
    fun `401 na data — gdy CSRF nie wystarcza, ciche logowanie ponawia request`() {
        coEvery { sessionRefresher.refreshCsrf() } returns true
        coEvery { sessionRefresher.reauthenticate() } returns ReauthResult.SUCCESS
        val chain = chain(WEB_URL, 401, 401, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(3, chain.proceeded.size)
    }

    @Test
    fun `401 na data — brak zapisanych poswiadczen nie wylogowuje`() {
        coEvery { sessionRefresher.refreshCsrf() } returns false
        coEvery { sessionRefresher.reauthenticate() } returns ReauthResult.NO_CREDENTIALS
        val chain = chain(WEB_URL, 401)

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        verify(exactly = 0) { sessionRepository.setLoggedIn(false) }
    }

    @Test
    fun `401 na data — awaria sieci przy odnawianiu nie wylogowuje`() {
        coEvery { sessionRefresher.refreshCsrf() } returns false
        coEvery { sessionRefresher.reauthenticate() } returns ReauthResult.UNAVAILABLE
        val chain = chain(WEB_URL, 401)

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        verify(exactly = 0) { sessionRepository.setLoggedIn(false) }
    }

    @Test
    fun `401 na data — odrzucone poswiadczenia wylogowuja i czyszcza magazyn`() {
        coEvery { sessionRefresher.refreshCsrf() } returns false
        coEvery { sessionRefresher.reauthenticate() } returns ReauthResult.REJECTED
        val chain = chain(WEB_URL, 401)

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        verify { sessionRefresher.forgetCredentials() }
        verify { sessionRepository.setToken(null) }
        verify { sessionRepository.setLoggedIn(false) }
    }

    @Test
    fun `401 na api — odswieza Bearer token i ponawia`() {
        coEvery { sessionRefresher.refreshApiToken() } returns ReauthResult.SUCCESS
        val chain = chain(API_URL, 401, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, chain.proceeded.size)
    }

    @Test
    fun `ponowiony request nie wpada w petle odnawiania`() {
        val request = Request.Builder().url(WEB_URL).header("X-CCI-Reauth", "1").build()
        val chain = FakeChain(request, mutableListOf(401))

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        assertEquals(1, chain.proceeded.size)
        verify(exactly = 0) { sessionRepository.setLoggedIn(any()) }
    }

    @Test
    fun `401 spoza data i api jest przepuszczane bez odnawiania`() {
        val chain = chain("https://crowncaps.info/auth/login", 401)

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        assertEquals(1, chain.proceeded.size)
    }

    /** Minimalny łańcuch: oddaje kolejne kody z listy i zapamiętuje wysłane requesty. */
    private class FakeChain(
        private val request: Request,
        private val codes: MutableList<Int>
    ) : Interceptor.Chain {

        val proceeded = mutableListOf<Request>()

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded += request
            return Response.Builder()
                .code(codes.removeAt(0))
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .message("test")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}

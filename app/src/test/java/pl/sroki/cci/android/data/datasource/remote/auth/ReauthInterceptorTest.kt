package pl.sroki.cci.android.data.datasource.remote.auth

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.ReauthResult
import pl.sroki.cci.android.data.SessionRefresher
import pl.sroki.cci.android.data.SessionRepository

class ReauthInterceptorTest {

    private lateinit var sessionRefresher: SessionRefresher
    private lateinit var sessionRepository: SessionRepository
    private lateinit var interceptor: ReauthInterceptor

    /** Requesty, które trafiły do chain.proceed() — w kolejności. */
    private val proceeded = mutableListOf<Request>()

    private companion object {
        const val WEB_URL = "https://crowncaps.info/data/catalog/caps/1/collection"
        const val API_URL = "https://crowncaps.info/api/v1/caps/1"
    }

    @Before
    fun setUp() {
        sessionRefresher = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        interceptor = ReauthInterceptor(dagger.Lazy { sessionRefresher }, sessionRepository)
        proceeded.clear()
    }

    private fun chain(url: String, vararg codes: Int) =
        chainFor(Request.Builder().url(url).build(), codes.toMutableList())

    /**
     * Łańcuch oddający kolejne kody z listy. Zmockowany, a nie zaimplementowany ręcznie:
     * OkHttp 5.4 dołożył do Interceptor.Chain kilkanaście metod `with*`, których ta atrapa
     * nie potrzebuje, a każde kolejne rozszerzenie interfejsu psułoby kompilację testu.
     */
    private fun chainFor(request: Request, codes: MutableList<Int>): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            val sent = firstArg<Request>()
            proceeded += sent
            Response.Builder()
                .code(codes.removeAt(0))
                .request(sent)
                .protocol(Protocol.HTTP_1_1)
                .message("test")
                .body("".toResponseBody(null))
                .build()
        }
        return chain
    }

    @Test
    fun `odpowiedz sukcesem przechodzi bez odswiezania sesji`() {
        val chain = chain(WEB_URL, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(1, proceeded.size)
    }

    @Test
    fun `419 na data — samo odswiezenie CSRF ratuje request`() {
        coEvery { sessionRefresher.refreshCsrf() } returns true
        val chain = chain(WEB_URL, 419, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, proceeded.size)
        assertNotNull(proceeded[1].header("X-CCI-Reauth"))
    }

    @Test
    fun `401 na data — gdy CSRF nie wystarcza, ciche logowanie ponawia request`() {
        coEvery { sessionRefresher.refreshCsrf() } returns true
        coEvery { sessionRefresher.reauthenticate() } returns ReauthResult.SUCCESS
        val chain = chain(WEB_URL, 401, 401, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(3, proceeded.size)
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
        assertEquals(2, proceeded.size)
    }

    @Test
    fun `ponowiony request nie wpada w petle odnawiania`() {
        val request = Request.Builder().url(WEB_URL).header("X-CCI-Reauth", "1").build()
        val chain = chainFor(request, mutableListOf(401))

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        assertEquals(1, proceeded.size)
        verify(exactly = 0) { sessionRepository.setLoggedIn(any()) }
    }

    @Test
    fun `401 spoza data i api jest przepuszczane bez odnawiania`() {
        val chain = chain("https://crowncaps.info/auth/login", 401)

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        assertEquals(1, proceeded.size)
    }
}

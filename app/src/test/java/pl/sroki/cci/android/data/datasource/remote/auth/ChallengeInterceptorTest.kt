package pl.sroki.cci.android.data.datasource.remote.auth

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChallengeInterceptorTest {

    private lateinit var clearanceStore: ClearanceStore
    private lateinit var interceptor: ChallengeInterceptor
    private val proceeded = mutableListOf<Request>()

    private companion object {
        const val URL = "https://crowncaps.info/api/v1/caps/1"
    }

    @Before
    fun setUp() {
        clearanceStore = mockk(relaxed = true)
        interceptor = ChallengeInterceptor(clearanceStore)
        proceeded.clear()
    }

    /** Odpowiedź z nagłówkiem Cloudflare albo bez, zależnie od flagi na danej pozycji listy. */
    private fun chain(request: Request, responses: MutableList<Pair<Int, Boolean>>): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            val sent = firstArg<Request>()
            proceeded += sent
            val (code, challenge) = responses.removeAt(0)
            Response.Builder()
                .code(code)
                .request(sent)
                .protocol(Protocol.HTTP_1_1)
                .message("test")
                .apply { if (challenge) header("cf-mitigated", "challenge") }
                .body("".toResponseBody(null))
                .build()
        }
        return chain
    }

    private fun request() = Request.Builder().url(URL).build()

    @Test
    fun `odpowiedz bez naglowka Cloudflare przechodzi bez czekania`() {
        val chain = chain(request(), mutableListOf(200 to false))

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(1, proceeded.size)
    }

    @Test
    fun `po zdobyciu clearance zadanie ponawia sie i przechodzi`() {
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(true)
        val chain = chain(request(), mutableListOf(403 to true, 200 to false))

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, proceeded.size)
    }

    @Test
    fun `gdy pierwsze clearance nie wystarcza, ponawia jeszcze raz`() {
        // Świeży cf_clearance bywa od razu ponownie oflagowany — druga próba już przechodzi.
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(true)
        val chain = chain(request(), mutableListOf(403 to true, 403 to true, 200 to false))

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(3, proceeded.size)
    }

    @Test
    fun `gdy uzytkownik zamknie challenge, wraca pierwotny 403`() {
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(false)
        val chain = chain(request(), mutableListOf(403 to true))

        val response = interceptor.intercept(chain)

        assertEquals(403, response.code)
        assertEquals(1, proceeded.size)
    }

    @Test
    fun `rezygnacja zeruje stan, zeby kolejny challenge znow otworzyl WebView`() {
        // Bez tego flaga zostawała podniesiona, a niedokończony sygnał trafiał do kolejnych
        // żądań — nic już nie otwierało WebView i wszystko wisiało do restartu aplikacji.
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(false)
        val chain = chain(request(), mutableListOf(403 to true))

        interceptor.intercept(chain)

        io.mockk.verify(exactly = 1) { clearanceStore.abandonChallenge() }
    }

    @Test
    fun `udane clearance nie zeruje stanu`() {
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(true)
        val chain = chain(request(), mutableListOf(403 to true, 200 to false))

        interceptor.intercept(chain)

        io.mockk.verify(exactly = 0) { clearanceStore.abandonChallenge() }
    }

    @Test
    fun `po wyczerpaniu prob oddaje 403 zamiast wisiec`() {
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(true)
        // Bramka uparcie flaguje mimo clearance — po MAX_ATTEMPTS przestajemy próbować.
        val chain = chain(request(), mutableListOf(403 to true, 403 to true, 403 to true, 403 to true))

        val response = interceptor.intercept(chain)

        assertEquals(403, response.code)
        // Wywołanie pierwotne + 3 ponowienia.
        assertEquals(4, proceeded.size)
        io.mockk.verify(exactly = 3) { clearanceStore.requireChallenge() }
    }
}

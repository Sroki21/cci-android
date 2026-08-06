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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun request(marker: Boolean = false) = Request.Builder()
        .url(URL)
        .apply { if (marker) header("X-CCI-Clearance", "1") }
        .build()

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
        // Ponowienie niesie znacznik, żeby uparta bramka nie zapętliła żądania.
        assertNotNull(proceeded[1].header("X-CCI-Clearance"))
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
    fun `juz ponowione zadanie nie czeka drugi raz`() {
        val chain = chain(request(marker = true), mutableListOf(403 to true))

        val response = interceptor.intercept(chain)

        assertEquals(403, response.code)
        assertEquals(1, proceeded.size)
        // requireChallenge nie powinno być w ogóle wołane — nie ma drugiego okna challengu.
        io.mockk.verify(exactly = 0) { clearanceStore.requireChallenge() }
    }

    @Test
    fun `pierwsze proceed idzie z oryginalnym zadaniem bez znacznika`() {
        every { clearanceStore.requireChallenge() } returns CompletableDeferred(true)
        val chain = chain(request(), mutableListOf(403 to true, 200 to false))

        interceptor.intercept(chain)

        assertNull(proceeded[0].header("X-CCI-Clearance"))
    }
}

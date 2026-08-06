package pl.sroki.cci.android.data.datasource.remote

import io.mockk.every
import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductFilterInterceptorTest {

    private val interceptor = ProductFilterInterceptor()

    /** Zwraca URL, z którym żądanie faktycznie poszło dalej. */
    private fun send(request: Request): String {
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        var sent: Request? = null
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            sent = firstArg()
            Response.Builder()
                .code(200)
                .request(sent!!)
                .protocol(Protocol.HTTP_1_1)
                .message("test")
                .body("".toResponseBody(null))
                .build()
        }
        interceptor.intercept(chain)
        return sent!!.url.toString()
    }

    private fun get(url: String) = send(Request.Builder().url(url).build())

    private fun productId(url: String) =
        url.toHttpUrl().queryParameter("productId")

    @Test
    fun `lista kapsli dostaje filtr produktu`() {
        assertEquals("1", productId(get("https://crowncaps.info/api/v1/caps")))
    }

    @Test
    fun `lista kapsli danego kraju tez jest lista`() {
        assertEquals("1", productId(get("https://crowncaps.info/api/v1/countries/5/caps")))
    }

    @Test
    fun `lista kapsli danej kategorii tez jest lista`() {
        assertEquals("1", productId(get("https://crowncaps.info/api/v1/categories/caps")))
    }

    @Test
    fun `szczegol kapsla nie dostaje filtru`() {
        // Ścieżka kończy się liczbą, nie „/caps" — filtr zawężałby zapytanie o konkretny kapsel.
        assertNull(productId(get("https://crowncaps.info/api/v1/caps/123")))
    }

    @Test
    fun `zapytanie o kolekcje nie dostaje filtru`() {
        // Backend gubi przy nim część kapsli — te listy filtruje się po stronie klienta.
        val url = get("https://crowncaps.info/api/v1/caps?in_collection=true")

        assertNull(productId(url))
    }

    @Test
    fun `zadanie inne niz GET nie dostaje filtru`() {
        val post = Request.Builder()
            .url("https://crowncaps.info/data/catalog/caps/search")
            .post("{}".toRequestBody(null))
            .build()

        assertNull(productId(send(post)))
    }

    @Test
    fun `istniejace parametry zapytania zostaja zachowane`() {
        val url = get("https://crowncaps.info/api/v1/caps?page=3")

        assertEquals("3", url.toHttpUrl().queryParameter("page"))
        assertEquals("1", productId(url))
    }
}

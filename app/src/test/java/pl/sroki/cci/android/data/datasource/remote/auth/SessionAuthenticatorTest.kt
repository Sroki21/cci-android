package pl.sroki.cci.android.data.datasource.remote.auth

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.SessionRepository

class SessionAuthenticatorTest {

    private lateinit var sessionRepository: SessionRepository
    private lateinit var authenticator: SessionAuthenticator

    @Before
    fun setUp() {
        sessionRepository = SessionRepository(mockContext(), dagger.Lazy { mockk(relaxed = true) })
        authenticator = SessionAuthenticator(sessionRepository)
    }

    private fun mockContext(): Context {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("api_token", null) } returns null
        every { prefs.getString("user_name", null) } returns null
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences("session", Context.MODE_PRIVATE) } returns prefs
        return context
    }

    private fun build401Response(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .build()
        return Response.Builder()
            .code(401)
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .build()
    }

    @Test
    fun `authenticate — 401 z api — czysci token i ustawia isLoggedIn=false`() {
        sessionRepository.setLoggedIn(true)
        sessionRepository.setToken("token-123")

        val result = authenticator.authenticate(
            route = null,
            response = build401Response("https://crowncaps.info/api/v1/caps/1")
        )

        assertFalse(sessionRepository.isLoggedIn.value)
        assertNull(sessionRepository.token.value)
        assertNull(result)
    }

    @Test
    fun `authenticate — 401 z web-route — zostawia sesje i token nietkniete`() {
        sessionRepository.setLoggedIn(true)
        sessionRepository.setToken("token-123")

        val result = authenticator.authenticate(
            route = null,
            response = build401Response("https://crowncaps.info/data/catalog/caps/1/collection")
        )

        assertTrue(sessionRepository.isLoggedIn.value)
        assertEquals("token-123", sessionRepository.token.value)
        assertNull(result)
    }
}

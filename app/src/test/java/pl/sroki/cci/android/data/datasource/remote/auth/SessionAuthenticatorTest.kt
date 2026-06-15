package pl.sroki.cci.android.data.datasource.remote.auth

import android.content.Context
import android.content.SharedPreferences
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.SessionRepository

class SessionAuthenticatorTest {

    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var sessionRepository: SessionRepository
    private lateinit var authenticator: SessionAuthenticator

    @Before
    fun setUp() {
        cookieJar = mockk(relaxed = true)
        sessionRepository = SessionRepository(mockContext())
        authenticator = SessionAuthenticator(cookieJar, sessionRepository)
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

    private fun build401Response(): Response {
        val request = Request.Builder()
            .url("https://crowncaps.info/api")
            .build()
        return Response.Builder()
            .code(401)
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("Unauthorized")
            .build()
    }

    @Test
    fun `authenticate — 401 — czysci cookies i ustawia isLoggedIn=false`() {
        sessionRepository.setLoggedIn(true)

        val result = authenticator.authenticate(route = null, response = build401Response())

        assertFalse(sessionRepository.isLoggedIn.value)
        verify { cookieJar.clear() }
        assertNull(result)
    }

    @Test
    fun `authenticate — zwraca null (brak retry)`() {
        val result = authenticator.authenticate(route = null, response = build401Response())

        assertNull(result)
    }
}

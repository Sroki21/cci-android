package pl.sroki.cci.android.data

import android.content.Context
import android.content.SharedPreferences
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.model.LoginRequest
import retrofit2.Response

class AuthRepositoryTest {

    private lateinit var authApiService: AuthApiService
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var sessionRepository: SessionRepository

    @Before
    fun setUp() {
        authApiService = mockk()
        cookieJar = mockk(relaxed = true)
        sessionRepository = SessionRepository(mockContext())
        coEvery { authApiService.apiToken(any()) } returns mockk(relaxed = true)
    }

    private fun mockContext(): Context {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("api_token", null) } returns null
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences("session", Context.MODE_PRIVATE) } returns prefs
        return context
    }

    @Test
    fun `init — cookie istnieje — isLoggedIn true`() {
        val sessionCookie = Cookie.Builder()
            .name("crowncapsinfo-session")
            .value("abc123")
            .domain("crowncaps.info")
            .build()
        every { cookieJar.loadForRequest(any()) } returns listOf(sessionCookie)

        AuthRepository(authApiService, sessionRepository, cookieJar)

        assertTrue(sessionRepository.isLoggedIn.value)
    }

    @Test
    fun `init — brak cookie — isLoggedIn false`() {
        every { cookieJar.loadForRequest(any()) } returns emptyList()

        AuthRepository(authApiService, sessionRepository, cookieJar)

        assertEquals(false, sessionRepository.isLoggedIn.value)
    }

    @Test
    fun `login sukces — isLoggedIn true, Result success`() = runTest {
        every { cookieJar.loadForRequest(any()) } returns emptyList()
        val repo = AuthRepository(authApiService, sessionRepository, cookieJar)
        coEvery { authApiService.initCsrf() } returns Response.success(Unit)
        coEvery { authApiService.login(any()) } returns Response.success("{}".toResponseBody("application/json".toMediaType()))

        val result = repo.login("user@example.com", "password")

        assertTrue(result.isSuccess)
        assertTrue(sessionRepository.isLoggedIn.value)
    }

    @Test
    fun `login blad 422 — isLoggedIn false, Result failure z komunikatem`() = runTest {
        every { cookieJar.loadForRequest(any()) } returns emptyList()
        val repo = AuthRepository(authApiService, sessionRepository, cookieJar)
        coEvery { authApiService.initCsrf() } returns Response.success(Unit)
        val errorBody = """{"errors":{"email":["These credentials do not match our records."]}}"""
            .toResponseBody("application/json".toMediaType())
        coEvery { authApiService.login(any()) } returns Response.error(422, errorBody)

        val result = repo.login("wrong@example.com", "wrong")

        assertTrue(result.isFailure)
        assertEquals("These credentials do not match our records.", result.exceptionOrNull()?.message)
        assertEquals(false, sessionRepository.isLoggedIn.value)
    }

    @Test
    fun `logout — isLoggedIn false, cookies wyczyszczone`() = runTest {
        every { cookieJar.loadForRequest(any()) } returns emptyList()
        val repo = AuthRepository(authApiService, sessionRepository, cookieJar)
        sessionRepository.setLoggedIn(true)
        coEvery { authApiService.logout() } returns Response.success(Unit)

        repo.logout()

        assertEquals(false, sessionRepository.isLoggedIn.value)
        verify { cookieJar.clear() }
    }
}

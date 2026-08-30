package pl.sroki.cci.android.data

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.CredentialsStore
import pl.sroki.cci.android.data.datasource.remote.auth.AuthApiService
import pl.sroki.cci.android.data.datasource.remote.auth.WebSessionCookies
import retrofit2.Response

/**
 * Sedno testu: ciche logowanie musi zaczynać od czystego jara, a skrót przez okno świeżości
 * nie może przykryć jara zatrutego już PO ostatnim udanym logowaniu.
 *
 * Mechanika samego czyszczenia siedzi w [WebSessionCookies] i ma własne testy — tutaj chodzi
 * o to, czy `SessionRefresher` woła ją wtedy, kiedy trzeba.
 */
class SessionRefresherTest {

    private lateinit var authApiService: AuthApiService
    private lateinit var credentialsStore: CredentialsStore
    private lateinit var sessionRepository: SessionRepository
    private lateinit var webSessionCookies: WebSessionCookies
    private lateinit var refresher: SessionRefresher

    @Before
    fun setUp() {
        authApiService = mockk(relaxed = true)
        credentialsStore = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        webSessionCookies = mockk(relaxed = true)
        every { webSessionCookies.isDuplicated() } returns false

        every { credentialsStore.load() } returns
            CredentialsStore.Credentials("kolekcjoner@example.com", "haslo")
        coEvery { authApiService.initCsrf() } returns Response.success(Unit)
        coEvery { authApiService.login(any()) } returns Response.success(
            "".toResponseBody(null)
        )

        refresher = SessionRefresher(
            authApiService, credentialsStore, sessionRepository, webSessionCookies
        )
    }

    @Test
    fun `cookies sesji sa porzucane przed logowaniem`() = runTest {
        assertEquals(ReauthResult.SUCCESS, refresher.reauthenticate())

        // Kolejność jest istotna: czyszczenie po initCsrf() wyrzuciłoby świeży XSRF-TOKEN.
        coVerifyOrder {
            webSessionCookies.drop()
            authApiService.initCsrf()
            authApiService.login(any())
        }
    }

    @Test
    fun `powtorka w oknie swiezosci nie loguje ponownie`() = runTest {
        refresher.reauthenticate()

        assertEquals(ReauthResult.SUCCESS, refresher.reauthenticate())

        coVerifyOrder { authApiService.login(any()) }
        verify(exactly = 1) { webSessionCookies.drop() }
    }

    @Test
    fun `zatruty jar w oknie swiezosci wymusza ponowne logowanie`() = runTest {
        // Challenge Cloudflare chodzi współbieżnie z odnawianiem, więc gościnne cookies potrafią
        // wjechać do jara w te dziesięć sekund PO udanym logowaniu. Wtedy „sukces sprzed chwili"
        // jest nieaktualny: ponowienie dostałoby 401, a RETRY_MARKER blokuje drugą rundę.
        refresher.reauthenticate()
        every { webSessionCookies.isDuplicated() } returns true

        assertEquals(ReauthResult.SUCCESS, refresher.reauthenticate())

        verify(exactly = 2) { webSessionCookies.drop() }
    }

    @Test
    fun `brak poswiadczen nie rusza jara`() = runTest {
        every { credentialsStore.load() } returns null

        assertEquals(ReauthResult.NO_CREDENTIALS, refresher.reauthenticate())

        verify(exactly = 0) { webSessionCookies.drop() }
    }

    @Test
    fun `odrzucone poswiadczenia zwracaja REJECTED`() = runTest {
        coEvery { authApiService.login(any()) } returns Response.error(
            401,
            "".toResponseBody(null)
        )

        assertEquals(ReauthResult.REJECTED, refresher.reauthenticate())
    }

    @Test
    fun `padnieta siec nie wylogowuje`() = runTest {
        coEvery { authApiService.login(any()) } throws java.io.IOException("brak sieci")

        assertEquals(ReauthResult.UNAVAILABLE, refresher.reauthenticate())
    }
}

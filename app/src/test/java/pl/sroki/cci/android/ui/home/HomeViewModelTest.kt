package pl.sroki.cci.android.ui.home

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.SessionRepository

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionRepository = SessionRepository(mockContext())
        viewModel = HomeViewModel(sessionRepository)
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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state — not logged in, no userName`() = runTest {
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.userName)
    }

    @Test
    fun `after login — isLoggedIn true and userName set`() = runTest {
        sessionRepository.setLoggedIn(true)
        sessionRepository.setUserName("user@example.com")

        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertEquals("user@example.com", viewModel.uiState.value.userName)
    }

    @Test
    fun `after logout — isLoggedIn false and userName null`() = runTest {
        sessionRepository.setLoggedIn(true)
        sessionRepository.setUserName("user@example.com")

        sessionRepository.setLoggedIn(false)
        sessionRepository.setUserName(null)

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.userName)
    }
}

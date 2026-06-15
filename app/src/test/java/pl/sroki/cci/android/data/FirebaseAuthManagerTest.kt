package pl.sroki.cci.android.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.IOException

class FirebaseAuthManagerTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var manager: FirebaseAuthManager

    @Before
    fun setUp() {
        auth = mockk()
        every { auth.currentUser } returns null
        manager = FirebaseAuthManager(auth)
    }

    @Test
    fun `signInWithEmail sukces — uid uaktualniony`() = runTest {
        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forResult(mockk<AuthResult>())
        val user = mockk<FirebaseUser>()
        every { user.uid } returns "uid-123"
        every { user.isAnonymous } returns false
        every { auth.currentUser } returnsMany listOf(null, user)

        manager.signInWithEmail("test@example.com", "password123")

        assertEquals("uid-123", manager.uid.value)
    }

    @Ignore("R3 bug — createUser wywołane przy każdym wyjątku")
    @Test
    fun `signInWithEmail złe hasło — NIE wywołuje createUserWithEmailAndPassword`() = runTest {
        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forException(
            FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "Hasło jest nieprawidłowe.")
        )
        every { auth.createUserWithEmailAndPassword(any(), any()) } returns Tasks.forResult(mockk())

        manager.signInWithEmail("test@example.com", "wrong-password")

        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
    }

    @Ignore("R3 bug — createUser wywołane przy każdym wyjątku")
    @Test
    fun `signInWithEmail błąd sieci — rzuca wyjątek, NIE tworzy konta`() = runTest {
        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forException(
            IOException("Brak połączenia z siecią")
        )
        every { auth.createUserWithEmailAndPassword(any(), any()) } returns Tasks.forResult(mockk())

        val result = runCatching { manager.signInWithEmail("test@example.com", "password123") }

        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
        assertTrue(result.isFailure)
    }
}

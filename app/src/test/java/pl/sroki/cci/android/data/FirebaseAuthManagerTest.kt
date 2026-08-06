package pl.sroki.cci.android.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Logowanie do Firebase idzie wyłącznie po udanym logowaniu do crowncaps, więc e-mail i hasło są
 * już przez backend przyjęte. Scenariusz, który psuł synchronizację: zmiana hasła na crowncaps —
 * logowanie webowe przechodzi z nowym hasłem, Firebase zna jeszcze poprzednie, a catch-and-create
 * kończył się kolizją kont. Wyjątek ginął w `runCatching` w AuthRepository, `uid` zostawał `null`
 * i wszystkie zapisy do Firestore (stojące pod `if (uid != null)`) cicho przestawały działać.
 */
class FirebaseAuthManagerTest {

    private companion object {
        const val EMAIL = "test@example.com"
        const val NOWE_HASLO = "nowe-haslo"
        const val STARE_HASLO = "stare-haslo"
        const val UID = "uid-123"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var manager: FirebaseAuthManager
    private lateinit var user: FirebaseUser

    /** Kto jest zalogowany w Firebase — mock `currentUser` czyta to pole na bieżąco. */
    private var zalogowany: FirebaseUser? = null

    @Before
    fun setUp() {
        zalogowany = null
        user = mockk(relaxed = true)
        every { user.uid } returns UID
        every { user.isAnonymous } returns false

        auth = mockk()
        every { auth.currentUser } answers { zalogowany }
        // FirebaseAuthManager rejestruje AuthStateListener w init — patrz H4.
        every { auth.addAuthStateListener(any()) } just Runs
        manager = FirebaseAuthManager(auth)
    }

    /** Udane logowanie tym hasłem — ustawia currentUser, tak jak zrobiłby to Firebase. */
    private fun logowanieDziala(haslo: String) {
        every { auth.signInWithEmailAndPassword(EMAIL, haslo) } answers {
            zalogowany = user
            Tasks.forResult(mockk<AuthResult>())
        }
    }

    private fun logowanieOdrzucone(haslo: String) {
        every { auth.signInWithEmailAndPassword(EMAIL, haslo) } returns Tasks.forException(
            FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "Hasło jest nieprawidłowe.")
        )
    }

    @Test
    fun `sukces logowania ustawia uid`() = runTest {
        logowanieDziala(NOWE_HASLO)

        manager.signInWithEmail(EMAIL, NOWE_HASLO)

        assertEquals(UID, manager.uid.value)
    }

    @Test
    fun `nieistniejace konto jest zakladane`() = runTest {
        every { auth.signInWithEmailAndPassword(EMAIL, NOWE_HASLO) } returns Tasks.forException(
            FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "Konta nie ma.")
        )
        every { auth.createUserWithEmailAndPassword(EMAIL, NOWE_HASLO) } answers {
            zalogowany = user
            Tasks.forResult(mockk<AuthResult>())
        }

        manager.signInWithEmail(EMAIL, NOWE_HASLO)

        assertEquals(UID, manager.uid.value)
    }

    /**
     * Przy włączonej ochronie przed enumeracją e-maili brak konta wraca jako
     * `ERROR_INVALID_CREDENTIAL`, nie `ERROR_USER_NOT_FOUND` — pierwsze logowanie musi mimo to
     * założyć konto.
     */
    @Test
    fun `brak konta zglaszany jako zle poswiadczenia tez zaklada konto`() = runTest {
        logowanieOdrzucone(NOWE_HASLO)
        every { auth.createUserWithEmailAndPassword(EMAIL, NOWE_HASLO) } answers {
            zalogowany = user
            Tasks.forResult(mockk<AuthResult>())
        }

        manager.signInWithEmail(EMAIL, NOWE_HASLO)

        assertEquals(UID, manager.uid.value)
    }

    /** Sedno H1: hasło zmienione na crowncaps, Firebase zna jeszcze poprzednie. */
    @Test
    fun `zmiana hasla — logowanie poprzednim i podniesienie hasla konta`() = runTest {
        logowanieOdrzucone(NOWE_HASLO)
        logowanieDziala(STARE_HASLO)
        every { user.updatePassword(NOWE_HASLO) } returns Tasks.forResult(null)

        manager.signInWithEmail(EMAIL, NOWE_HASLO, previousPassword = STARE_HASLO)

        assertEquals(UID, manager.uid.value)
        verify(exactly = 1) { user.updatePassword(NOWE_HASLO) }
        // Konto istnieje — zakładanie go byłoby błędem.
        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
    }

    /**
     * Regresja: przy złym haśle do istniejącego konta wynik musi być głośny. Wcześniej
     * `createUserWithEmailAndPassword` leciało bezwarunkowo, a kolizja kont wychodziła poza
     * `signInWithEmail` jako wyjątek bez kontekstu.
     */
    @Test
    fun `zle haslo do istniejacego konta — wyjatek i brak uid`() = runTest {
        logowanieOdrzucone(NOWE_HASLO)
        every { auth.createUserWithEmailAndPassword(EMAIL, NOWE_HASLO) } returns Tasks.forException(
            FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Konto już istnieje.")
        )

        val wynik = runCatching { manager.signInWithEmail(EMAIL, NOWE_HASLO) }

        assertTrue(wynik.isFailure)
        assertTrue(wynik.exceptionOrNull() is FirebaseAuthInvalidCredentialsException)
        assertNull(manager.uid.value)
    }

    /** Nieudana migracja nie może po cichu wpaść w zakładanie konta ani zamilknąć. */
    @Test
    fun `poprzednie haslo tez odrzucone — wyjatek i brak uid`() = runTest {
        logowanieOdrzucone(NOWE_HASLO)
        logowanieOdrzucone(STARE_HASLO)
        every { auth.createUserWithEmailAndPassword(EMAIL, NOWE_HASLO) } returns Tasks.forException(
            FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Konto już istnieje.")
        )

        val wynik = runCatching {
            manager.signInWithEmail(EMAIL, NOWE_HASLO, previousPassword = STARE_HASLO)
        }

        assertTrue(wynik.isFailure)
        assertNull(manager.uid.value)
    }

    @Test
    fun `blad sieci — wyjatek leci dalej i konto nie powstaje`() = runTest {
        every { auth.signInWithEmailAndPassword(EMAIL, NOWE_HASLO) } returns Tasks.forException(
            IOException("Brak połączenia z siecią")
        )
        every { auth.createUserWithEmailAndPassword(any(), any()) } returns Tasks.forResult(mockk())

        val wynik = runCatching { manager.signInWithEmail(EMAIL, NOWE_HASLO) }

        assertTrue(wynik.isFailure)
        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
        assertNull(manager.uid.value)
    }

    /** Zalogowana sesja Firebase nie może być ruszana — żadnych zbędnych żądań. */
    @Test
    fun `juz zalogowany — bez zadnego zapytania do Firebase`() = runTest {
        zalogowany = user

        manager.signInWithEmail(EMAIL, NOWE_HASLO, previousPassword = STARE_HASLO)

        assertEquals(UID, manager.uid.value)
        verify(exactly = 0) { auth.signInWithEmailAndPassword(any(), any()) }
        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
    }
}

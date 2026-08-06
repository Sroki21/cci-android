package pl.sroki.cci.android.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.model.BinderCapCount

/**
 * Deduplikacja klaserów kasowała dane użytkownika: `DELETE FROM binder WHERE id NOT IN
 * (SELECT MAX(id) FROM binder GROUP BY name)` leciało przy każdym starcie aplikacji, a nazwa
 * klasera nie ma UNIQUE — dwa ręcznie utworzone klasery "Belgia" znaczyły, że kolejne
 * uruchomienie kasowało starszy razem z kapslami (kaskada FK).
 */
class FirestoreRestoreDedupTest {

    private lateinit var binderDao: BinderDao
    private lateinit var zapisaneWersje: MutableMap<String, Int>
    private lateinit var useCase: FirestoreRestoreUseCase

    @Before
    fun setUp() {
        zapisaneWersje = mutableMapOf()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val klucz = slot<String>()
        val wartosc = slot<Int>()
        every { editor.putInt(capture(klucz), capture(wartosc)) } answers {
            zapisaneWersje[klucz.captured] = wartosc.captured
            editor
        }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(any(), any()) } answers {
            zapisaneWersje[firstArg()] ?: secondArg()
        }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        binderDao = mockk(relaxed = true)
        useCase = FirestoreRestoreUseCase(
            context = context,
            authManager = mockk(relaxed = true),
            database = mockk(relaxed = true),
            binderDao = binderDao,
            binderPageDao = mockk(relaxed = true),
            capPositionDao = mockk(relaxed = true),
            capCacheDao = mockk(relaxed = true),
            binderService = mockk(relaxed = true),
            binderPageService = mockk(relaxed = true),
            capPositionService = mockk(relaxed = true),
            purchasedCapsService = mockk(relaxed = true),
            purchasedCapsLocalStore = mockk(relaxed = true)
        )
    }

    /**
     * Regresja G1: pod starym SQL-em zostawał wyłącznie MAX(id), czyli pusty klaser utworzony
     * przed chwilą, a 30 kapsli ze starszego szło do kosza bez ostrzeżenia i bez logu.
     */
    @Test
    fun `duplikat nazwy z kapslami przezywa, ginie tylko pusty`() = runTest {
        coEvery { binderDao.getCapCounts() } returns listOf(
            BinderCapCount(id = 1L, name = "Belgia", capCount = 30),
            BinderCapCount(id = 2L, name = "Belgia", capCount = 0)
        )

        useCase.deduplicateRoomData()

        coVerify(exactly = 1) { binderDao.deleteById(2L) }
        coVerify(exactly = 0) { binderDao.deleteById(1L) }
    }

    @Test
    fun `gdy kapsle maja obie kopie, zadna nie jest kasowana`() = runTest {
        coEvery { binderDao.getCapCounts() } returns listOf(
            BinderCapCount(id = 1L, name = "Belgia", capCount = 30),
            BinderCapCount(id = 2L, name = "Belgia", capCount = 4)
        )

        useCase.deduplicateRoomData()

        coVerify(exactly = 0) { binderDao.deleteById(any()) }
    }

    @Test
    fun `klaser o unikalnej nazwie zostaje, nawet pusty`() = runTest {
        coEvery { binderDao.getCapCounts() } returns listOf(
            BinderCapCount(id = 1L, name = "Belgia", capCount = 30),
            BinderCapCount(id = 2L, name = "Nowy", capCount = 0)
        )

        useCase.deduplicateRoomData()

        coVerify(exactly = 0) { binderDao.deleteById(any()) }
    }

    /** Przy remisie zostaje najstarszy — to on ma stronę w Firestore i historię użytkownika. */
    @Test
    fun `przy dwoch pustych duplikatach zostaje najstarszy`() = runTest {
        coEvery { binderDao.getCapCounts() } returns listOf(
            BinderCapCount(id = 7L, name = "Belgia", capCount = 0),
            BinderCapCount(id = 3L, name = "Belgia", capCount = 0)
        )

        useCase.deduplicateRoomData()

        coVerify(exactly = 1) { binderDao.deleteById(7L) }
        coVerify(exactly = 0) { binderDao.deleteById(3L) }
    }

    /**
     * Sprzątanie ma być jednorazowe. Przy każdym starcie karałoby klasery o powtórzonej nazwie
     * tworzone później ręcznie — dokładnie ten mechanizm kasował dane.
     */
    @Test
    fun `drugie uruchomienie nie zaglada juz do bazy`() = runTest {
        coEvery { binderDao.getCapCounts() } returns listOf(
            BinderCapCount(id = 1L, name = "Belgia", capCount = 30),
            BinderCapCount(id = 2L, name = "Belgia", capCount = 0)
        )

        useCase.deduplicateRoomData()
        useCase.deduplicateRoomData()

        coVerify(exactly = 1) { binderDao.getCapCounts() }
        coVerify(exactly = 1) { binderDao.deleteById(2L) }
    }
}

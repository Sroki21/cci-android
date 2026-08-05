package pl.sroki.cci.android.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.datasource.remote.firestore.PurchasedCapsFirestoreService

/**
 * Lista "zakupionych" żyła wyłącznie w SharedPreferences i ginęła przy reinstalacji.
 * Te testy pilnują dwutorowego zapisu oraz tego, że zbędne zapisy nie idą do chmury.
 */
class PurchasedCapsLocalStoreTest {

    private companion object {
        const val UID = "test-uid"
    }

    private lateinit var zapisane: MutableSet<String>
    private lateinit var firestore: PurchasedCapsFirestoreService
    private lateinit var store: PurchasedCapsLocalStore

    @Before
    fun setUp() {
        zapisane = mutableSetOf()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val przechwycone = slot<Set<String>>()
        every { editor.putStringSet(any(), capture(przechwycone)) } answers {
            zapisane = przechwycone.captured.toMutableSet()
            editor
        }
        val prefs = mockk<SharedPreferences>()
        every { prefs.getStringSet(any(), null) } answers { zapisane }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences("purchased_caps", Context.MODE_PRIVATE) } returns prefs

        firestore = mockk(relaxed = true)
        val authManager = mockk<FirebaseAuthManager>()
        every { authManager.uid } returns MutableStateFlow(UID)

        store = PurchasedCapsLocalStore(context, firestore, authManager)
    }

    @Test
    fun `dodanie kapsla zapisuje lokalnie i w Firestore`() {
        store.add(165216L)

        assertEquals(setOf(165216L), store.getIds())
        verify(exactly = 1) { firestore.scheduleAdd(UID, 165216L) }
    }

    @Test
    fun `ponowne dodanie tego samego kapsla nie generuje zapisu do chmury`() {
        store.add(165216L)
        store.add(165216L)

        verify(exactly = 1) { firestore.scheduleAdd(UID, 165216L) }
    }

    @Test
    fun `usuniecie kapsla zdejmuje go lokalnie i w Firestore`() {
        store.add(165216L)
        store.remove(165216L)

        assertEquals(emptySet<Long>(), store.getIds())
        verify(exactly = 1) { firestore.scheduleRemove(UID, 165216L) }
    }

    @Test
    fun `usuniecie nieobecnego kapsla nie idzie do chmury`() {
        store.remove(999L)

        verify(exactly = 0) { firestore.scheduleRemove(any(), any()) }
    }

    @Test
    fun `odtworzenie z chmury nie wypycha danych z powrotem`() {
        store.replaceAllLocally(setOf(1L, 2L, 3L))

        assertEquals(setOf(1L, 2L, 3L), store.getIds())
        verify(exactly = 0) { firestore.scheduleAdd(any(), any()) }
        verify(exactly = 0) { firestore.scheduleReplaceAll(any(), any()) }
    }
}

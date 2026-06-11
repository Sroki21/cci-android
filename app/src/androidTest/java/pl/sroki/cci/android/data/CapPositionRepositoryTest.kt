package pl.sroki.cci.android.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService

@RunWith(AndroidJUnit4::class)
class CapPositionRepositoryTest {

    private lateinit var db: CciDatabase
    private lateinit var repo: CapPositionRepository
    private var binderPageId: Long = 0L

    @Before
    fun createDb() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CapPositionRepository(
            dao = db.capPositionDao(),
            binderPageDao = db.binderPageDao(),
            capPositionFirestoreService = CapPositionFirestoreService(FirebaseFirestore.getInstance()),
            authManager = FirebaseAuthManager(FirebaseAuth.getInstance())
        )
        val binderId = db.binderDao().insert(Binder(name = "Test"))
        binderPageId = db.binderPageDao().insert(BinderPage(binderId = binderId, pageNumber = 1))
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun assign_andGetByPage() = runBlocking {
        repo.assign(binderPageId, 1, 42L)
        val positions = repo.getByPage(binderPageId).first()
        assertEquals(1, positions.size)
        assertEquals(42L, positions[0].capId)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun assign_duplicateSlot_throws() = runBlocking {
        repo.assign(binderPageId, 1, 42L)
        repo.assign(binderPageId, 1, 99L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun assign_positionOutOfRange_throws() = runBlocking {
        repo.assign(binderPageId, 36, 42L)
    }

    @Test
    fun reassign_movesCapToNewSlot() = runBlocking {
        repo.assign(binderPageId, 1, 42L)
        repo.reassign(42L, binderPageId, 5)
        assertNull(repo.getByPage(binderPageId).first().firstOrNull { it.position == 1 })
        assertNotNull(repo.getByPage(binderPageId).first().firstOrNull { it.position == 5 })
    }

    @Test
    fun unassign_removesEntry() = runBlocking {
        repo.assign(binderPageId, 1, 42L)
        repo.unassign(42L)
        val positions = repo.getByPage(binderPageId).first()
        assertEquals(0, positions.size)
    }
}

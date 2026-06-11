package pl.sroki.cci.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition

@RunWith(AndroidJUnit4::class)
class BinderRepositoryTest {

    private lateinit var db: CciDatabase
    private lateinit var repo: BinderRepository

    @Before
    fun createDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BinderRepository(db.binderDao(), db.capPositionDao())
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun create_andGetAll() = runBlocking {
        repo.create("Europa 1")
        repo.create("Azja 1")
        val binders = repo.getAll().first()
        assertEquals(2, binders.size)
    }

    @Test
    fun delete_emptyBinder_succeeds() = runBlocking {
        val id = repo.create("Europa 1")
        repo.delete(id)
        val binders = repo.getAll().first()
        assertEquals(0, binders.size)
    }

    @Test(expected = IllegalStateException::class)
    fun delete_occupiedBinder_throws() = runBlocking {
        val binderId = repo.create("Europa 1")
        val pageId = db.binderPageDao().insert(BinderPage(binderId = binderId, pageNumber = 1))
        db.capPositionDao().insert(CapPosition(binderPageId = pageId, position = 1, capId = 100L))

        repo.delete(binderId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun create_blankName_throws() = runBlocking {
        repo.create("   ")
    }
}

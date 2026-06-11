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
import pl.sroki.cci.android.data.datasource.local.dao.PendingCapDao
import pl.sroki.cci.android.data.datasource.local.entity.PendingCap

@RunWith(AndroidJUnit4::class)
class PendingCapDaoTest {

    private lateinit var db: CciDatabase
    private lateinit var dao: PendingCapDao

    @Before
    fun createDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pendingCapDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insert_andGetAll() = runBlocking {
        dao.insert(PendingCap(1L))
        dao.insert(PendingCap(2L))
        val result = dao.getAll().first()
        assertEquals(2, result.size)
    }

    @Test
    fun insert_idempotent_ignoresDuplicate() = runBlocking {
        dao.insert(PendingCap(1L))
        dao.insert(PendingCap(1L))
        val result = dao.getAll().first()
        assertEquals(1, result.size)
    }

    @Test
    fun deleteById_removesEntry() = runBlocking {
        dao.insert(PendingCap(1L))
        dao.deleteById(1L)
        val result = dao.getAll().first()
        assertEquals(0, result.size)
    }

    @Test
    fun exists_returnsCorrectCount() = runBlocking {
        dao.insert(PendingCap(42L))
        assertEquals(1, dao.exists(42L))
        assertEquals(0, dao.exists(99L))
    }
}

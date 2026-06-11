package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage

@Dao
interface BinderPageDao {

    @Insert
    suspend fun insert(page: BinderPage): Long

    @Query("DELETE FROM binder_page WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM binder_page WHERE binder_id = :binderId ORDER BY page_number")
    fun getByBinderId(binderId: Long): Flow<List<BinderPage>>

    @Query("SELECT COUNT(*) FROM binder_page WHERE binder_id = :binderId")
    suspend fun countByBinderId(binderId: Long): Int
}

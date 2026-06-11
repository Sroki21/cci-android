package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition

@Dao
interface CapPositionDao {

    @Insert
    suspend fun insert(pos: CapPosition): Long

    @Delete
    suspend fun delete(pos: CapPosition)

    @Update
    suspend fun update(pos: CapPosition)

    @Query("SELECT * FROM cap_position WHERE binder_page_id = :binderPageId")
    fun getByPage(binderPageId: Long): Flow<List<CapPosition>>

    @Query("SELECT * FROM cap_position WHERE cap_id = :capId LIMIT 1")
    suspend fun getByCapId(capId: Long): CapPosition?

    @Query("DELETE FROM cap_position WHERE cap_id = :capId")
    suspend fun deleteByCapId(capId: Long)

    @Query(
        """
        SELECT COUNT(*) FROM cap_position
        WHERE binder_page_id IN (SELECT id FROM binder_page WHERE binder_id = :binderId)
        """
    )
    suspend fun countByBinderId(binderId: Long): Int

    @Transaction
    suspend fun reassign(capId: Long, newBinderPageId: Long, newPosition: Int) {
        deleteByCapId(capId)
        insert(CapPosition(binderPageId = newBinderPageId, position = newPosition, capId = capId))
    }
}

package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.PendingCap

@Dao
interface PendingCapDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cap: PendingCap): Long

    @Query("DELETE FROM pending_cap WHERE cap_id = :capId")
    suspend fun deleteById(capId: Long)

    @Query("SELECT * FROM pending_cap")
    fun getAll(): Flow<List<PendingCap>>

    @Query("SELECT COUNT(*) FROM pending_cap WHERE cap_id = :capId")
    suspend fun exists(capId: Long): Int
}

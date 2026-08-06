package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition

@Dao
interface CapPositionDao {

    @Insert
    suspend fun insert(pos: CapPosition): Long

    // Restore z Firestore: pomijaj duplikaty (binder_page_id, position) zamiast przerywać
    // całą pętlę wyjątkiem UNIQUE. Pojedynczy konfliktowy slot nie może uciąć reszty kapsli.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(pos: CapPosition): Long

    @Query("SELECT * FROM cap_position WHERE binder_page_id = :binderPageId")
    fun getByPage(binderPageId: Long): Flow<List<CapPosition>>

    @Query("SELECT * FROM cap_position WHERE cap_id = :capId LIMIT 1")
    suspend fun getByCapId(capId: Long): CapPosition?

    // Kto zajmuje dany slot — do zaraportowania pozycji pominiętej przy odtwarzaniu.
    @Query("SELECT cap_id FROM cap_position WHERE binder_page_id = :binderPageId AND position = :position LIMIT 1")
    suspend fun getCapIdAt(binderPageId: Long, position: Int): Long?

    @Query("DELETE FROM cap_position WHERE cap_id = :capId")
    suspend fun deleteByCapId(capId: Long)

    @Query("""
        SELECT b.name as binderName, bp.page_number as pageNumber, cp.position as position
        FROM cap_position cp
        JOIN binder_page bp ON cp.binder_page_id = bp.id
        JOIN binder b ON bp.binder_id = b.id
        WHERE cp.cap_id = :capId
        LIMIT 1
    """)
    suspend fun getBinderInfoByCapId(capId: Long): CapBinderInfo?

    @Query(
        """
        SELECT COUNT(*) FROM cap_position
        WHERE binder_page_id IN (SELECT id FROM binder_page WHERE binder_id = :binderId)
        """
    )
    suspend fun countByBinderId(binderId: Long): Int

    @Query("SELECT cap_id FROM cap_position")
    suspend fun getAllCapIds(): List<Long>

    @Query("SELECT cap_id FROM cap_position")
    fun getAllCapIdsFlow(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM cap_position")
    suspend fun countAll(): Int

    @Transaction
    suspend fun reassignFull(capId: Long, newPos: CapPosition) {
        deleteByCapId(capId)
        insert(newPos)
    }
}

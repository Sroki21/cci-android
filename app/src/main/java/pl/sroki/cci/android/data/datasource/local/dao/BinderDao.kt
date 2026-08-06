package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.model.BinderCapCount

@Dao
interface BinderDao {

    @Insert
    suspend fun insert(binder: Binder): Long

    @Query("DELETE FROM binder WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Kaskada FK usuwa też binder_page i cap_position (ON DELETE CASCADE).
    @Query("DELETE FROM binder")
    suspend fun deleteAll()

    @Query("SELECT * FROM binder ORDER BY name")
    fun getAll(): Flow<List<Binder>>

    @Query("SELECT * FROM binder WHERE id = :id")
    suspend fun getById(id: Long): Binder?

    @Query("SELECT COUNT(*) FROM binder")
    suspend fun countAll(): Int

    // Liczba kapsli per klaser. Deduplikacja decyduje na tej podstawie, co wolno usunąć —
    // wcześniej kasowała wszystko poza MAX(id) i zabierała pełne klasery razem z kapslami.
    @Query(
        """
        SELECT b.id AS id, b.name AS name,
               (SELECT COUNT(*) FROM cap_position cp
                  JOIN binder_page bp ON cp.binder_page_id = bp.id
                 WHERE bp.binder_id = b.id) AS capCount
        FROM binder b
        """
    )
    suspend fun getCapCounts(): List<BinderCapCount>
}

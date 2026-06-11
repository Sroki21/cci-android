package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.Binder

@Dao
interface BinderDao {

    @Insert
    suspend fun insert(binder: Binder): Long

    @Query("DELETE FROM binder WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM binder ORDER BY name")
    fun getAll(): Flow<List<Binder>>

    @Query("SELECT * FROM binder WHERE id = :id")
    suspend fun getById(id: Long): Binder?

    @Query("SELECT COUNT(*) FROM binder")
    suspend fun countAll(): Int
}

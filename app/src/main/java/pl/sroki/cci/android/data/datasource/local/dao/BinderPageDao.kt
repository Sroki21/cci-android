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

    @Query("UPDATE binder_page SET page_number = :pageNumber WHERE id = :id")
    suspend fun updatePageNumber(id: Long, pageNumber: Int)

    @Query("UPDATE binder_page SET binder_id = :binderId WHERE id = :id")
    suspend fun updateBinderId(id: Long, binderId: Long)

    @Query("SELECT * FROM binder_page WHERE binder_id = :binderId ORDER BY page_number")
    fun getByBinderId(binderId: Long): Flow<List<BinderPage>>

    /** Jednorazowy odczyt (nie Flow) — do wywołania wewnątrz withTransaction, gdzie Flow.first() by się zawiesił. */
    @Query("SELECT * FROM binder_page WHERE binder_id = :binderId ORDER BY page_number")
    suspend fun getByBinderIdOnce(binderId: Long): List<BinderPage>

    @Query("SELECT COUNT(*) FROM binder_page WHERE binder_id = :binderId")
    suspend fun countByBinderId(binderId: Long): Int

    // Najwyższy zajęty numer strony. Nowa strona idzie na koniec, więc liczy się MAX, nie COUNT:
    // po usunięciu strony ze środka (1, 2, 3 -> 1, 3) COUNT+1 wskazywał numer 3, który już istnieje,
    // i wstawienie leciało na UNIQUE(binder_id, page_number).
    @Query("SELECT COALESCE(MAX(page_number), 0) FROM binder_page WHERE binder_id = :binderId")
    suspend fun maxPageNumber(binderId: Long): Int

    @Query("SELECT * FROM binder_page WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BinderPage?
}

package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import pl.sroki.cci.android.data.datasource.local.entity.CountryFlag

@Dao
interface CountryFlagDao {

    @Query("SELECT * FROM country_flag")
    suspend fun getAll(): List<CountryFlag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(flags: List<CountryFlag>)
}

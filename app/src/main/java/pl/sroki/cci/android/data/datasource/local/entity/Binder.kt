package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "binder")
data class Binder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

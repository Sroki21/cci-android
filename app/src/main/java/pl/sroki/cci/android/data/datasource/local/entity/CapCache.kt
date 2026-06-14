package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cap_cache")
data class CapCache(
    @PrimaryKey @ColumnInfo(name = "cap_id") val capId: Long,
    @ColumnInfo(name = "country") val country: String = "",
    @ColumnInfo(name = "image_url") val imageUrl: String = ""
)

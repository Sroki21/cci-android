package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "country_flag")
data class CountryFlag(
    @PrimaryKey @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "image_url") val imageUrl: String
)

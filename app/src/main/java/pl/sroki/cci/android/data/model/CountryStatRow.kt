package pl.sroki.cci.android.data.model

import androidx.room.ColumnInfo

data class CountryStatRow(
    @ColumnInfo(name = "country") val country: String,
    @ColumnInfo(name = "count") val count: Int
)

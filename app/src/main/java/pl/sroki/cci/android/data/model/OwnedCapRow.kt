package pl.sroki.cci.android.data.model

import androidx.room.ColumnInfo

data class OwnedCapRow(
    @ColumnInfo(name = "capId") val capId: Long,
    @ColumnInfo(name = "imageUrl") val imageUrl: String
)

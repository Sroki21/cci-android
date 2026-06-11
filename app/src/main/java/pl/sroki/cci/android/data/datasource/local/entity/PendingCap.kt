package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_cap")
data class PendingCap(
    @PrimaryKey @ColumnInfo(name = "cap_id") val capId: Long
)

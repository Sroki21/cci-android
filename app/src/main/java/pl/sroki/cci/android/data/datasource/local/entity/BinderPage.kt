package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "binder_page",
    foreignKeys = [
        ForeignKey(
            entity = Binder::class,
            parentColumns = ["id"],
            childColumns = ["binder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["binder_id", "page_number"], unique = true),
        Index("binder_id")
    ]
)
data class BinderPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "binder_id") val binderId: Long,
    @ColumnInfo(name = "page_number") val pageNumber: Int,
    @ColumnInfo(name = "firestore_id") val firestoreId: String? = null
)

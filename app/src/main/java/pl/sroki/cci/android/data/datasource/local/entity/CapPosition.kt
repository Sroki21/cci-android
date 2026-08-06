package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cap_position",
    foreignKeys = [
        ForeignKey(
            entity = BinderPage::class,
            parentColumns = ["id"],
            childColumns = ["binder_page_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["binder_page_id", "position"], unique = true),
        Index("binder_page_id"),
        // cap_id jest kluczem wyszukiwania w pięciu zapytaniach, w tym w dwóch Flow
        // przeliczanych po każdej zmianie tabeli. Bez indeksu każde z nich robiło pełny skan.
        Index("cap_id")
    ]
)
data class CapPosition(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "binder_page_id") val binderPageId: Long,
    val position: Int,
    @ColumnInfo(name = "cap_id") val capId: Long,
    @ColumnInfo(name = "firestore_id") val firestoreId: String? = null
)

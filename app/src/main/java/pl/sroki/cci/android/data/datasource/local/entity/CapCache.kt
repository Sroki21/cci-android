package pl.sroki.cci.android.data.datasource.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cap_cache")
data class CapCache(
    @PrimaryKey @ColumnInfo(name = "cap_id") val capId: Long,
    @ColumnInfo(name = "country") val country: String = "",
    @ColumnInfo(name = "image_url") val imageUrl: String = "",
    // Snapshot identyfikujący kapsel (odporność na zmiany w katalogu crowncaps).
    @ColumnInfo(name = "name") val name: String = "",
    // Fingerprint do wykrywania rozjazdów (createdAt/createdById niezmienne; updatedAt = tania zmiana).
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "created_by_id") val createdById: Int? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    // Stan weryfikacji.
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long? = null,
    @ColumnInfo(name = "catalog_status") val catalogStatus: String = "unknown",
    // Ręczny wybór producenta dla kapsli "-Multiple countries" — nadpisuje country/producer
    // ponad surowy wpis z API. selectedProducerId identyfikuje wpis w cap.producers do
    // porównania przy weryfikacji (patrz CollectionVerifier).
    @ColumnInfo(name = "selected_producer_id") val selectedProducerId: Int? = null,
    @ColumnInfo(name = "producer") val producer: String = ""
)

package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import pl.sroki.cci.android.data.model.CountryStatRow
import pl.sroki.cci.android.data.model.OwnedCapRow

@Dao
interface CapCacheDao {

    @Query("SELECT country FROM cap_cache WHERE cap_id = :capId LIMIT 1")
    suspend fun getCountry(capId: Long): String?

    @Query("SELECT * FROM cap_cache WHERE cap_id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<CapCache>

    // Zapisuje tylko kraj, nie ruszając już zapisanego image_url (Statystyki/Detal).
    @Query("""
        INSERT INTO cap_cache (cap_id, country, image_url) VALUES (:capId, :country, '')
        ON CONFLICT(cap_id) DO UPDATE SET country = :country
    """)
    suspend fun upsertCountry(capId: Long, country: String)

    // Pełny wpis z kraju i zdjęcia (zakładka Klasery).
    @Query("""
        INSERT INTO cap_cache (cap_id, country, image_url) VALUES (:capId, :country, :imageUrl)
        ON CONFLICT(cap_id) DO UPDATE SET country = :country, image_url = :imageUrl
    """)
    suspend fun upsertFull(capId: Long, country: String, imageUrl: String)

    // Snapshot identyfikujący kapsel + fingerprint; nie rusza pól weryfikacji
    // (last_verified_at, catalog_status), więc bezpieczny przy ponownym zapisie.
    @Query("""
        INSERT INTO cap_cache (cap_id, name, country, image_url, created_at, created_by_id, updated_at)
        VALUES (:capId, :name, :country, :imageUrl, :createdAt, :createdById, :updatedAt)
        ON CONFLICT(cap_id) DO UPDATE SET
            name = :name, country = :country, image_url = :imageUrl,
            created_at = :createdAt, created_by_id = :createdById, updated_at = :updatedAt
    """)
    suspend fun upsertSnapshot(
        capId: Long,
        name: String,
        country: String,
        imageUrl: String,
        createdAt: String?,
        createdById: Int?,
        updatedAt: String?
    )

    // Zapis wyniku weryfikacji.
    @Query("UPDATE cap_cache SET catalog_status = :status, last_verified_at = :verifiedAt WHERE cap_id = :capId")
    suspend fun markVerified(capId: Long, status: String, verifiedAt: Long)

    // Kolejka do weryfikacji: wstawione kapsle, najdawniej (lub nigdy) weryfikowane najpierw.
    @Query("""
        SELECT cp.cap_id FROM cap_position cp
        LEFT JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        GROUP BY cp.cap_id
        ORDER BY (MAX(cc.last_verified_at) IS NULL) DESC, MAX(cc.last_verified_at) ASC
        LIMIT :limit
    """)
    suspend fun getCapIdsToVerify(limit: Int): List<Long>

    // Liczba wstawionych kapsli z rozjazdem (odznaka) — reaktywnie.
    @Query("""
        SELECT COUNT(DISTINCT cp.cap_id) FROM cap_position cp
        JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.catalog_status IN ('missing', 'swapped', 'updated')
    """)
    fun flaggedCountFlow(): Flow<Int>

    // Wstawione kapsle z rozjazdem (ekran przeglądu) — reaktywnie.
    @Query("""
        SELECT cc.* FROM cap_cache cc
        WHERE cc.cap_id IN (SELECT DISTINCT cap_id FROM cap_position)
          AND cc.catalog_status IN ('missing', 'swapped', 'updated')
        ORDER BY cc.catalog_status, cc.name
    """)
    fun flaggedCapsFlow(): Flow<List<CapCache>>

    @Query("""
        SELECT cp.cap_id FROM cap_position cp
        LEFT JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.cap_id IS NULL OR cc.country = ''
    """)
    suspend fun getMissingForPositioned(): List<Long>

    @Query("""
        SELECT cc.country, COUNT(*) as count
        FROM cap_position cp
        JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.country != ''
        GROUP BY cc.country
        ORDER BY count DESC
    """)
    suspend fun getCountryStats(): List<CountryStatRow>

    // Posiadane kapsle danego kraju (z lokalnego cache) — capId + zdjęcie, bez API.
    @Query("""
        SELECT DISTINCT cc.cap_id as capId, cc.image_url as imageUrl
        FROM cap_position cp
        JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.country = :country
        ORDER BY cc.cap_id DESC
    """)
    suspend fun getOwnedCapsByCountry(country: String): List<OwnedCapRow>
}

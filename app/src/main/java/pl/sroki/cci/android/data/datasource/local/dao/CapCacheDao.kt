package pl.sroki.cci.android.data.datasource.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import pl.sroki.cci.android.data.model.CountryStatRow
import pl.sroki.cci.android.data.model.OwnedCapRow

@Dao
abstract class CapCacheDao {

    @Query("SELECT country FROM cap_cache WHERE cap_id = :capId LIMIT 1")
    abstract suspend fun getCountry(capId: Long): String?

    @Query("SELECT * FROM cap_cache WHERE cap_id IN (:ids)")
    abstract suspend fun getByIdsChunk(ids: List<Long>): List<CapCache>

    // SQLite ma limit zmiennych w zapytaniu (999 na starszych Androidach), a IN (:ids) zużywa
    // po jednej na wpis — dłuższa lista rzucała "too many SQL variables". Dziś uśpione, bo jedyny
    // wołający podaje kapsle jednej strony klasera, ale limit nie zależy od nas.
    open suspend fun getByIds(ids: List<Long>): List<CapCache> =
        ids.chunked(900).flatMap { getByIdsChunk(it) }

    // Zapisuje tylko kraj, nie ruszając już zapisanego image_url (Statystyki/Detal).
    // country pod warunkiem — patrz [upsertSnapshot].
    @Query("""
        INSERT INTO cap_cache (cap_id, country, image_url, name, catalog_status, producer, image_unavailable)
        VALUES (:capId, :country, '', '', 'unknown', '', 0)
        ON CONFLICT(cap_id) DO UPDATE SET
            country = CASE WHEN selected_producer_id IS NULL THEN :country ELSE country END
    """)
    abstract suspend fun upsertCountry(capId: Long, country: String)

    // Pełny wpis z kraju i zdjęcia (zakładka Klasery). Niepusty image_url zdejmuje znacznik
    // braku zdjęcia — katalog mógł je w międzyczasie dodać.
    // country pod warunkiem — patrz [upsertSnapshot]. Tędy szła najczęstsza ścieżka kasowania
    // ręcznego wyboru: backfill zdjęć w zakładce Klasery dotyczy właśnie kapsli wstawionych
    // do klaserów, a te mają wybór producenta.
    @Query("""
        INSERT INTO cap_cache (cap_id, country, image_url, name, catalog_status, producer, image_unavailable)
        VALUES (:capId, :country, :imageUrl, '', 'unknown', '', 0)
        ON CONFLICT(cap_id) DO UPDATE SET
            country = CASE WHEN selected_producer_id IS NULL THEN :country ELSE country END,
            image_url = :imageUrl,
            image_unavailable = CASE WHEN :imageUrl != '' THEN 0 ELSE image_unavailable END
    """)
    abstract suspend fun upsertFull(capId: Long, country: String, imageUrl: String)

    // Katalog nie ma zdjęcia dla tego kapsla (pusty image_url w odpowiedzi albo kapsel
    // w ogóle nie istnieje). Wstawiamy wiersz nawet dla nieznanego kapsla — inaczej nie ma
    // gdzie zapisać, że pytać nie warto.
    @Query("""
        INSERT INTO cap_cache (cap_id, country, image_url, name, catalog_status, producer, image_unavailable)
        VALUES (:capId, '', '', '', 'unknown', '', 1)
        ON CONFLICT(cap_id) DO UPDATE SET image_unavailable = 1
    """)
    abstract suspend fun markImageUnavailable(capId: Long)

    // Snapshot identyfikujący kapsel + fingerprint; nie rusza pól weryfikacji
    // (last_verified_at, catalog_status), więc bezpieczny przy ponownym zapisie.
    //
    // country pod warunkiem: ręczny wybór producenta (selected_producer_id) jest silniejszy niż
    // surowa wartość z katalogu. Dla kapsla "-Multiple countries" katalog oddaje stały placeholder,
    // więc bezwarunkowe nadpisanie zostawiało wiersz wewnętrznie sprzeczny — country z katalogu
    // obok producer/selected_producer_id z ręcznego wyboru — a CollectionVerifier porównywał go
    // z krajem wybranego producenta i stawiał UPDATED na kapslu, w którym katalog nic nie zmienił.
    @Query("""
        INSERT INTO cap_cache (cap_id, name, country, image_url, created_at, created_by_id, updated_at, catalog_status, producer, image_unavailable)
        VALUES (:capId, :name, :country, :imageUrl, :createdAt, :createdById, :updatedAt, 'unknown', '', 0)
        ON CONFLICT(cap_id) DO UPDATE SET
            name = :name, image_url = :imageUrl,
            country = CASE WHEN selected_producer_id IS NULL THEN :country ELSE country END,
            created_at = :createdAt, created_by_id = :createdById, updated_at = :updatedAt
    """)
    abstract suspend fun upsertSnapshot(
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
    abstract suspend fun markVerified(capId: Long, status: String, verifiedAt: Long)

    // Ręczny wybór producenta/kraju dla kapsla "-Multiple countries" — nadpisuje country/producer,
    // nie rusza pozostałych pól snapshotu (name/image_url/fingerprint).
    @Query("""
        INSERT INTO cap_cache (cap_id, country, producer, selected_producer_id, image_url, name, catalog_status, image_unavailable)
        VALUES (:capId, :country, :producer, :producerId, '', '', 'unknown', 0)
        ON CONFLICT(cap_id) DO UPDATE SET
            country = :country, producer = :producer, selected_producer_id = :producerId
    """)
    abstract suspend fun selectProducer(capId: Long, producerId: Int, producer: String, country: String)

    // Porzucenie ręcznego wyboru — po akceptacji stanu katalogu, gdy wybrany producent zniknął.
    @Query("""
        UPDATE cap_cache
        SET selected_producer_id = NULL, producer = '', country = :country
        WHERE cap_id = :capId
    """)
    abstract suspend fun clearProducerSelection(capId: Long, country: String)

    // Ręczne wybory producenta zapisane lokalnie — źródło dla jednorazowego backfillu do Firestore.
    @Query("SELECT * FROM cap_cache WHERE selected_producer_id IS NOT NULL")
    abstract suspend fun getWithProducerSelection(): List<CapCache>

    // Kolejka do weryfikacji: wstawione kapsle, najdawniej (lub nigdy) weryfikowane najpierw.
    @Query("""
        SELECT cp.cap_id FROM cap_position cp
        LEFT JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        GROUP BY cp.cap_id
        ORDER BY (MAX(cc.last_verified_at) IS NULL) DESC, MAX(cc.last_verified_at) ASC
        LIMIT :limit
    """)
    abstract suspend fun getCapIdsToVerify(limit: Int): List<Long>

    // Liczba wstawionych kapsli z rozjazdem (odznaka) — reaktywnie.
    // Zbiór oflagowanych definiowany NEGATYWNIE: wszystko poza ok/unknown. Lista pozytywna
    // wymagałaby aktualizacji tutaj przy każdym nowym statusie — dokładnie tak rozjechało się
    // BindersScreen.isFlagged, które nie znało producer_removed. Patrz CatalogStatus.isFlagged.
    @Query("""
        SELECT COUNT(DISTINCT cp.cap_id) FROM cap_position cp
        JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.catalog_status NOT IN ('ok', 'unknown')
    """)
    abstract fun flaggedCountFlow(): Flow<Int>

    // Wstawione kapsle z rozjazdem (ekran przeglądu) — reaktywnie.
    @Query("""
        SELECT cc.* FROM cap_cache cc
        WHERE cc.cap_id IN (SELECT DISTINCT cap_id FROM cap_position)
          AND cc.catalog_status NOT IN ('ok', 'unknown')
        ORDER BY cc.catalog_status, cc.name
    """)
    abstract fun flaggedCapsFlow(): Flow<List<CapCache>>

    @Query("""
        SELECT cp.cap_id FROM cap_position cp
        LEFT JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE (cc.cap_id IS NULL OR cc.country = '')
          AND COALESCE(cc.image_unavailable, 0) = 0
    """)
    abstract suspend fun getMissingForPositioned(): List<Long>

    @Query("""
        SELECT cc.country, COUNT(*) as count
        FROM cap_position cp
        JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.country != ''
        GROUP BY cc.country
        ORDER BY count DESC
    """)
    abstract suspend fun getCountryStats(): List<CountryStatRow>

    // Posiadane kapsle danego kraju (z lokalnego cache) — capId + zdjęcie, bez API.
    @Query("""
        SELECT DISTINCT cc.cap_id as capId, cc.image_url as imageUrl
        FROM cap_position cp
        JOIN cap_cache cc ON cp.cap_id = cc.cap_id
        WHERE cc.country = :country
        ORDER BY cc.cap_id DESC
    """)
    abstract suspend fun getOwnedCapsByCountry(country: String): List<OwnedCapRow>
}

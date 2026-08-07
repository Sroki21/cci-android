package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.CatalogStatus
import pl.sroki.cci.android.model.binder.CountryCapCount
import pl.sroki.cci.android.model.binder.OwnedCapThumbnail

@Singleton
class CapCacheRepository @Inject constructor(private val dao: CapCacheDao) {

    suspend fun getCountry(capId: Long): String? =
        dao.getCountry(capId)?.takeIf { it.isNotBlank() }

    suspend fun getByIds(ids: List<Long>): List<CachedCap> = dao.getByIds(ids).map { it.toCachedCap() }

    suspend fun getOne(capId: Long): CachedCap? =
        dao.getByIds(listOf(capId)).firstOrNull()?.toCachedCap()

    suspend fun upsert(capId: Long, country: String) =
        dao.upsertCountry(capId, country)

    suspend fun upsertFull(capId: Long, country: String, imageUrl: String) =
        dao.upsertFull(capId, country, imageUrl)

    suspend fun markImageUnavailable(capId: Long) = dao.markImageUnavailable(capId)

    suspend fun clearProducerSelection(capId: Long, country: String) =
        dao.clearProducerSelection(capId, country)

    suspend fun upsertSnapshot(
        capId: Long,
        name: String,
        country: String,
        imageUrl: String,
        createdAt: String?,
        createdById: Int?,
        updatedAt: String?
    ) = dao.upsertSnapshot(capId, name, country, imageUrl, createdAt, createdById, updatedAt)

    suspend fun markVerified(capId: Long, status: CatalogStatus, verifiedAt: Long) =
        dao.markVerified(capId, status.raw, verifiedAt)

    suspend fun selectProducer(capId: Long, producerId: Int, producer: String, country: String) =
        dao.selectProducer(capId, producerId, producer, country)

    suspend fun getCapIdsToVerify(limit: Int): List<Long> = dao.getCapIdsToVerify(limit)

    fun flaggedCountFlow(): Flow<Int> = dao.flaggedCountFlow()

    fun flaggedCapsFlow(): Flow<List<CachedCap>> =
        dao.flaggedCapsFlow().map { list -> list.map { it.toCachedCap() } }

    suspend fun getMissingForPositioned(): List<Long> = dao.getMissingForPositioned()

    // Mapowanie na granicy repozytorium: CountryStatRow/OwnedCapRow to wewnętrzne modele
    // zapytań Room (@ColumnInfo), nie powinny wyciekać do ViewModeli — patrz CLAUDE.md
    // (data/model/ = ściśle wewnętrzne dla warstwy danych).
    suspend fun getCountryStats(): List<CountryCapCount> =
        dao.getCountryStats().map { CountryCapCount(it.country, it.count) }

    suspend fun getOwnedCapsByCountry(country: String): List<OwnedCapThumbnail> =
        dao.getOwnedCapsByCountry(country).map { OwnedCapThumbnail(it.capId, it.imageUrl) }
}

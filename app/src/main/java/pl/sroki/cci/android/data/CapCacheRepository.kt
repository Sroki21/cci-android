package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import pl.sroki.cci.android.data.model.CountryStatRow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapCacheRepository @Inject constructor(private val dao: CapCacheDao) {

    suspend fun getCountry(capId: Long): String? =
        dao.getCountry(capId)?.takeIf { it.isNotBlank() }

    suspend fun getByIds(ids: List<Long>): List<CapCache> = dao.getByIds(ids)

    suspend fun upsert(capId: Long, country: String) =
        dao.upsertCountry(capId, country)

    suspend fun upsertFull(capId: Long, country: String, imageUrl: String) =
        dao.upsertFull(capId, country, imageUrl)

    suspend fun upsertSnapshot(
        capId: Long,
        name: String,
        country: String,
        imageUrl: String,
        createdAt: String?,
        createdById: Int?,
        updatedAt: String?
    ) = dao.upsertSnapshot(capId, name, country, imageUrl, createdAt, createdById, updatedAt)

    suspend fun markVerified(capId: Long, status: String, verifiedAt: Long) =
        dao.markVerified(capId, status, verifiedAt)

    suspend fun getMissingForPositioned(): List<Long> = dao.getMissingForPositioned()

    suspend fun getCountryStats(): List<CountryStatRow> = dao.getCountryStats()

    suspend fun getOwnedCapsByCountry(country: String) = dao.getOwnedCapsByCountry(country)
}

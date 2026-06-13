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

    suspend fun upsert(capId: Long, country: String) =
        dao.upsert(CapCache(capId, country))

    suspend fun getMissingForPositioned(): List<Long> = dao.getMissingForPositioned()

    suspend fun getCountryStats(): List<CountryStatRow> = dao.getCountryStats()
}

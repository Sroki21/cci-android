package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.sroki.cci.android.data.datasource.local.dao.PendingCapDao
import pl.sroki.cci.android.data.datasource.local.entity.PendingCap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingCapRepository @Inject constructor(
    private val dao: PendingCapDao
) {
    fun getAll(): Flow<List<Long>> = dao.getAll().map { list -> list.map { it.capId } }

    suspend fun add(capId: Long) {
        dao.insert(PendingCap(capId))
    }

    suspend fun remove(capId: Long) {
        dao.deleteById(capId)
    }
}

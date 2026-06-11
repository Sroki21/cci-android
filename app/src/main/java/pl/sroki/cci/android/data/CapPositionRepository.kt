package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapPositionRepository @Inject constructor(
    private val dao: CapPositionDao
) {
    fun getByPage(binderPageId: Long): Flow<List<CapPosition>> = dao.getByPage(binderPageId)

    suspend fun getByCapId(capId: Long): CapPosition? = dao.getByCapId(capId)

    suspend fun assign(binderPageId: Long, position: Int, capId: Long): Long {
        require(position in 1..35) { "Pozycja musi być w zakresie 1-35" }
        return dao.insert(CapPosition(binderPageId = binderPageId, position = position, capId = capId))
    }

    suspend fun reassign(capId: Long, newBinderPageId: Long, newPosition: Int) {
        require(newPosition in 1..35) { "Pozycja musi być w zakresie 1-35" }
        dao.reassign(capId, newBinderPageId, newPosition)
    }

    suspend fun unassign(capId: Long) {
        dao.deleteByCapId(capId)
    }
}

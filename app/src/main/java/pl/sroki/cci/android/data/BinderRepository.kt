package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderRepository @Inject constructor(
    private val binderDao: BinderDao,
    private val capPositionDao: CapPositionDao
) {
    fun getAll(): Flow<List<Binder>> = binderDao.getAll()

    suspend fun create(name: String): Long {
        require(name.isNotBlank()) { "Nazwa klasera nie może być pusta" }
        return binderDao.insert(Binder(name = name))
    }

    suspend fun delete(binderId: Long) {
        val occupied = capPositionDao.countByBinderId(binderId)
        check(occupied == 0) { "Klaser zawiera kapsle i nie może być usunięty" }
        binderDao.deleteById(binderId)
    }
}

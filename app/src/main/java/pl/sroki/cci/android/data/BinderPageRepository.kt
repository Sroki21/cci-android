package pl.sroki.cci.android.data

import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderPageRepository @Inject constructor(
    private val dao: BinderPageDao
) {
    fun getByBinder(binderId: Long): Flow<List<BinderPage>> = dao.getByBinderId(binderId)

    suspend fun addPage(binderId: Long): Long {
        val count = dao.countByBinderId(binderId)
        check(count < 15) { "Klaser może mieć maksymalnie 15 stron" }
        return dao.insert(BinderPage(binderId = binderId, pageNumber = count + 1))
    }

    suspend fun deletePage(pageId: Long) {
        dao.deleteById(pageId)
    }
}

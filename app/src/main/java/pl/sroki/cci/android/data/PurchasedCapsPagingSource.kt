package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapsSearchRequest

class PurchasedCapsPagingSource(
    private val capsRepository: CapsRepository
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? {
        return state.anchorPosition?.let { anchor ->
            val page = state.closestPageToPosition(anchor)
            page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        return try {
            val page = params.key ?: 1
            val result = capsRepository.searchByFilter(
                CapsSearchRequest(inCollection = true),
                page = page
            )
            LoadResult.Page(
                data = result.data.filter { it.isInCollection },
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (result.currentPage == result.lastPage) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

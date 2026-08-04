package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

private const val STARTING_KEY = 1

class PictureSearchCapsPagingSource @Inject constructor(
    private val categoryIds: List<Int>,
    private val capsRepository: CapsRepository
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(1) ?: anchorPage.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> =
        try {
            val page = params.key ?: STARTING_KEY
            val pagination = capsRepository.getByCategoryIds(categoryIds, page)
            LoadResult.Page(
                data = pagination.data,
                prevKey = if (page == STARTING_KEY) null else page - 1,
                nextKey = if (pagination.currentPage == pagination.lastPage) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
}
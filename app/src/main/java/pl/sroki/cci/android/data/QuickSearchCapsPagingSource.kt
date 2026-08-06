package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

private const val STARTING_KEY = 1

class QuickSearchCapsPagingSource @Inject constructor(
    private val query: String,
    private val capsRepository: CapsRepository
) : PagingSource<Int, Cap>() {

    // Powtórzony kapsel na styku stron wywalał listę — patrz PageDeduplicator.
    private val dedup = PageDeduplicator()

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(1) ?: anchorPage.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> =
        try {
            val page = params.key ?: STARTING_KEY
            val pagination = capsRepository.getByQuery(query = query, page = page)
            LoadResult.Page(
                data = dedup.odsiej(pagination.data),
                prevKey = if (page == STARTING_KEY) null else page - 1,
                nextKey = if (pagination.currentPage == pagination.lastPage) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
}
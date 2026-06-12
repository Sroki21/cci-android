package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import pl.sroki.cci.android.model.Cap

private const val STARTING_KEY = 1

class SimilarCapsPagingSource(
    private val imageBytes: ByteArray,
    private val mimeType: String,
    private val capsRepository: CapsRepository
) : PagingSource<Int, Cap>() {

    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? = state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        if (params.key != null && params.key != STARTING_KEY) return LoadResult.Page(
            data = emptyList(), prevKey = null, nextKey = null
        )
        return try {
            val requestBody = imageBytes.toRequestBody(mimeType.toMediaType())
            val part = MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
            val response = capsRepository.searchSimilar(part)
            LoadResult.Page(data = response.caps, prevKey = null, nextKey = null)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

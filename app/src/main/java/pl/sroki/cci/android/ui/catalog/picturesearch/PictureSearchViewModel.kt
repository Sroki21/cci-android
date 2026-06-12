package pl.sroki.cci.android.ui.catalog.picturesearch

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

private data class ImageData(val bytes: ByteArray, val mimeType: String)

@HiltViewModel
class PictureSearchViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    var selectedImageUri by mutableStateOf<Uri?>(null)
        private set

    var hasSearched by mutableStateOf(false)
        private set

    private val searchTrigger = MutableStateFlow<ImageData?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val caps: Flow<PagingData<Cap>> = searchTrigger
        .flatMapLatest { data ->
            if (data == null) flowOf(PagingData.empty())
            else Pager(
                pagingSourceFactory = { capsRepository.similarCapsPagingSource(data.bytes, data.mimeType) },
                config = PagingConfig(pageSize = Cap.PER_PAGE)
            ).flow
        }
        .cachedIn(viewModelScope)

    fun onImageSelected(uri: Uri) {
        selectedImageUri = uri
        hasSearched = false
    }

    fun search() {
        val uri = selectedImageUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@launch
            searchTrigger.value = ImageData(bytes, "image/jpeg")
            withContext(Dispatchers.Main) { hasSearched = true }
        }
    }
}

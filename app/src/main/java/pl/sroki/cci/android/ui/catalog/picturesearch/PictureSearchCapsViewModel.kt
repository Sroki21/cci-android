package pl.sroki.cci.android.ui.catalog.picturesearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

@HiltViewModel
class PictureSearchCapsViewModel @Inject constructor(private val repository: CapsRepository) :
    ViewModel() {

    var categoryIds: List<Int> = listOf()
    val caps: Flow<PagingData<Cap>> = Pager(
        pagingSourceFactory = { repository.pictureSearchCapsPagingSource(categoryIds) },
        config = PagingConfig(pageSize = Cap.PER_PAGE)
    ).flow.cachedIn(viewModelScope)

}
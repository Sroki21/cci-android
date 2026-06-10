package pl.sroki.cci.android.ui.catalog.latest

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
class LatestCapsViewModel @Inject constructor(private val repository: CapsRepository) :
    ViewModel() {

    val caps: Flow<PagingData<Cap>> = Pager(
        pagingSourceFactory = { repository.latestCapsPagingSource() },
        config = PagingConfig(pageSize = Cap.PER_PAGE)
    ).flow.cachedIn(viewModelScope)

}
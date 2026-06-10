package pl.sroki.cci.android.ui.catalog.caps.quicksearch

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
class QuickSearchViewModel @Inject constructor(private val repository: CapsRepository) :
    ViewModel() {

    var query: String = ""
    // API /api/v1/caps?query= ignores perPage — always returns 20 items per page
    val caps: Flow<PagingData<Cap>> = Pager(
        pagingSourceFactory = { repository.quickSearchCapsPagingSource(query) },
        config = PagingConfig(pageSize = 20)
    ).flow.cachedIn(viewModelScope)

}
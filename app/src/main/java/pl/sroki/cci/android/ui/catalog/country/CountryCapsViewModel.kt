package pl.sroki.cci.android.ui.catalog.country

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

@HiltViewModel
class CountryCapsViewModel @Inject constructor(private val repository: CapsRepository) :
    ViewModel() {

    var id: Int = 0
    private var pagingSource: PagingSource<Int, Cap>? = null

    val caps: Flow<PagingData<Cap>> = Pager(
        pagingSourceFactory = { repository.countryCapsPagingSource(id).also { pagingSource = it } },
        config = PagingConfig(pageSize = Cap.PER_PAGE)
    ).flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            repository.collectionChanged.collect { pagingSource?.invalidate() }
        }
    }
}

package pl.sroki.cci.android.ui.catalog.caps.quicksearch

import androidx.lifecycle.SavedStateHandle
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
class QuickSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CapsRepository,
) : ViewModel() {

    // Fraza z trasy, a nie publiczne pole ustawiane przez ekran w trakcie kompozycji. Tamten
    // wariant działał wyłącznie dzięki temu, że fabryka czyta pole leniwie przy pierwszym
    // ładowaniu — zmiana wartości po utworzeniu ViewModelu nie odświeżałaby cachedIn-owanego
    // strumienia. Tak samo robią już AdvancedSearchViewModel i CountryOwnedCapsViewModel.
    private val query: String = savedStateHandle.get<String>("query").orEmpty()
    private var pagingSource: PagingSource<Int, Cap>? = null

    // API /api/v1/caps?query= ignores perPage — always returns 20 items per page
    val caps: Flow<PagingData<Cap>> = Pager(
        pagingSourceFactory = { repository.quickSearchCapsPagingSource(query).also { pagingSource = it } },
        config = PagingConfig(pageSize = 20)
    ).flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            repository.collectionChanged.collect { pagingSource?.invalidate() }
        }
    }
}

package pl.sroki.cci.android.ui.catalog.caps.advanced

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

@HiltViewModel
class AdvancedSearchViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    private val countriesRepository: CountriesRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    var filter by mutableStateOf(AdvancedSearchFilter())
        private set

    var countries by mutableStateOf<List<Country>>(emptyList())
        private set

    private val _totalResults = MutableStateFlow<Int?>(null)
    val totalResults: StateFlow<Int?> = _totalResults.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = sessionRepository.isLoggedIn

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private var pagingSource: PagingSource<Int, Cap>? = null
    private val _filterTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val caps: Flow<PagingData<Cap>> = _filterTrigger
        .flatMapLatest { trigger ->
            if (trigger == 0) flowOf(PagingData.empty())
            else Pager(
                config = PagingConfig(pageSize = Cap.PER_PAGE),
                pagingSourceFactory = {
                    capsRepository.advancedSearchPagingSource(filter) { total ->
                        _totalResults.value = total
                    }.also { pagingSource = it }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            capsRepository.collectionChanged.collect { pagingSource?.invalidate() }
        }
        viewModelScope.launch {
            countries = try {
                countriesRepository.getCountries()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun updateFilter(updated: AdvancedSearchFilter) { filter = updated }

    fun search() {
        _hasSearched.value = true
        _totalResults.value = null
        _filterTrigger.value++
    }
}

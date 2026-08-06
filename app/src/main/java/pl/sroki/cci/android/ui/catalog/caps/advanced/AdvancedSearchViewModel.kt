package pl.sroki.cci.android.ui.catalog.caps.advanced

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.CountriesRepository
import pl.sroki.cci.android.data.ProducersRepository
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import javax.inject.Inject

@HiltViewModel
class AdvancedSearchViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    private val countriesRepository: CountriesRepository,
    private val producersRepository: ProducersRepository,
    private val sessionRepository: SessionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialProducer: String? = savedStateHandle.get<String>("producer")

    var filter by mutableStateOf(
        AdvancedSearchFilter(producerName = initialProducer ?: "")
    )
        private set

    var countries by mutableStateOf<List<Country>>(emptyList())
        private set

    private val _producerSuggestions = MutableStateFlow<List<String>>(emptyList())
    val producerSuggestions: StateFlow<List<String>> = _producerSuggestions.asStateFlow()

    private val _totalResults = MutableStateFlow<Int?>(null)
    val totalResults: StateFlow<Int?> = _totalResults.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = sessionRepository.isLoggedIn

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private var pagingSource: PagingSource<Int, Cap>? = null
    private val _filterTrigger = MutableStateFlow(0)
    private var producerSearchJob: Job? = null

    /**
     * Filtr, na którym stoją WYNIKI — zamrożony w chwili kliknięcia „Szukaj".
     *
     * Fabryka czytała wcześniej `filter`, czyli pole zmieniane przy każdym dotknięciu formularza.
     * Gdy przyszło `collectionChanged` i Pager tworzył nowy PagingSource (dodanie kapsla do
     * kolekcji unieważnia listę), brał BIEŻĄCĄ zawartość formularza: miałeś wyniki, zmieniałeś
     * kraj, nie klikałeś „Szukaj", dodawałeś kapsel — i lista przeskakiwała na wyniki filtra,
     * którego nigdy nie zatwierdziłeś.
     */
    private var zatwierdzonyFiltr: AdvancedSearchFilter = filter

    @OptIn(ExperimentalCoroutinesApi::class)
    val caps: Flow<PagingData<Cap>> = _filterTrigger
        .flatMapLatest { trigger ->
            if (trigger == 0) flowOf(PagingData.empty())
            else Pager(
                config = PagingConfig(pageSize = Cap.PER_PAGE),
                pagingSourceFactory = {
                    // Zerowanie tutaj, a nie tylko w search(): po invalidate() strony doliczały
                    // się do starej sumy, więc „Znaleziono: N" rosło przy każdej zmianie kolekcji.
                    _totalResults.value = null
                    capsRepository.advancedSearchPagingSource(zatwierdzonyFiltr) { pageCount, apiTotal ->
                        _totalResults.value = if (apiTotal != null) {
                            apiTotal
                        } else {
                            (_totalResults.value ?: 0) + pageCount
                        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pusty select kraju wygląda jak „w bazie nie ma krajów" — niech chociaż zostanie ślad.
                Log.w("CCI_UI", "nie udało się pobrać listy krajów do filtra", e)
                emptyList()
            }
        }
        if (!initialProducer.isNullOrBlank()) search()
    }

    fun updateFilter(updated: AdvancedSearchFilter) { filter = updated }

    fun searchProducers(query: String) {
        producerSearchJob?.cancel()
        if (query.length < 2) {
            _producerSuggestions.value = emptyList()
            return
        }
        producerSearchJob = viewModelScope.launch {
            delay(300)
            // catch (Exception) łapało też CancellationException z anulowanego jobu i przypisywało
            // pustą listę — kontynuacja potrafiła wykonać się PO tym, jak nowy job ustawił wyniki,
            // więc podpowiedzi znikały tuż po pojawieniu się.
            _producerSuggestions.value = try {
                producersRepository.searchProducers(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CCI_UI", "nie udało się pobrać podpowiedzi producentów", e)
                emptyList()
            }
        }
    }

    fun clearProducerSuggestions() {
        _producerSuggestions.value = emptyList()
    }

    fun search() {
        _hasSearched.value = true
        _totalResults.value = null
        // Zamrożenie filtra: od tej chwili wyniki (także po odświeżeniu) stoją na tym, co
        // użytkownik zatwierdził, a nie na bieżącej zawartości formularza.
        zatwierdzonyFiltr = filter
        _filterTrigger.value++
    }
}

package pl.sroki.cci.android.data

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.MultipartBody
import pl.sroki.cci.android.data.datasource.remote.CapApiService
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.CapsSearchRequest
import pl.sroki.cci.android.model.Page
import pl.sroki.cci.android.model.SimilarCapsResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapsRepository @Inject constructor(
    private val capApiService: CapApiService,
    private val purchasedCapsLocalStore: PurchasedCapsLocalStore,
    private val capPositionRepository: CapPositionRepository
) {

    private val perPage = Cap.PER_PAGE

    private val _collectionChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val collectionChanged: SharedFlow<Unit> = _collectionChanged

    fun countryCapsPagingSource(id: Int) = CountryCapsPagingSource(id, this)
    fun latestCapsPagingSource() = LatestCapsPagingSource(this)
    fun quickSearchCapsPagingSource(query: String) = QuickSearchCapsPagingSource(query, this)
    fun similarCapsPagingSource(imageBytes: ByteArray, mimeType: String) =
        SimilarCapsPagingSource(imageBytes, mimeType, this)

    suspend fun searchSimilar(image: MultipartBody.Part): SimilarCapsResponse {
        val response = capApiService.searchSimilar(image)
        return response.copy(caps = enrichWithLocalCollectionStatus(response.caps))
    }

    // Endpointy wyszukiwania po zdjęciu i po filtrach (producent) nie zwracają poprawnie
    // isInCollection dla wyników (CapGridCard zawsze pokazywał brak ramki, a client-side
    // filtr "tylko w kolekcji" w AdvancedSearchPagingSource wycinał wszystko). Dopinamy status
    // z lokalnej prawdy: pozycje w klaserach + lokalny magazyn zakupionych — to samo źródło,
    // z którego korzysta LocalAssignedCapIds na pozostałych ekranach.
    private suspend fun enrichWithLocalCollectionStatus(caps: List<Cap>): List<Cap> {
        if (caps.isEmpty()) return caps
        val assignedIds = capPositionRepository.getAllCapIds().toSet()
        val purchasedIds = purchasedCapsLocalStore.getIds()
        return caps.map { cap ->
            if (!cap.isInCollection && (cap.id in assignedIds || cap.id in purchasedIds)) {
                cap.copy(isInCollection = true)
            } else cap
        }
    }

    fun advancedSearchPagingSource(
        filter: pl.sroki.cci.android.model.AdvancedSearchFilter,
        onPageLoaded: (filteredCount: Int, apiTotal: Int?) -> Unit
    ) = AdvancedSearchPagingSource(filter, this, onPageLoaded)

    suspend fun getByCountryId(id: Int, page: Int = 1): Page<Cap> {
        return capApiService.getByCountryId(countryId = id, page = page, perPage = perPage)
    }

    suspend fun getLatest(page: Int = 1): Page<Cap> {
        return capApiService.getLatest(page = page, perPage = perPage)
    }

    suspend fun getById(id: Int): CapExtended {
        return capApiService.getById(id = id)
    }

    suspend fun getByQuery(query: String, page: Int = 1): Page<Cap> {
        return capApiService.getByQuery(query = query, page = page, perPage = perPage)
    }

    suspend fun advancedSearch(
        query: String?,
        countryId: Int?,
        producer: String?,
        inCollection: Int?,
        page: Int = 1
    ): Page<Cap> {
        return capApiService.advancedSearch(
            query = query,
            countryId = countryId,
            producer = producer,
            inCollection = inCollection,
            page = page,
            perPage = perPage
        )
    }

    suspend fun searchByFilter(request: CapsSearchRequest, page: Int = 1): Page<Cap> {
        val result = capApiService.searchCapsByFilter(request, page, perPage)
        return result.copy(data = enrichWithLocalCollectionStatus(result.data))
    }

    suspend fun addToCollection(id: Int) {
        val resp = capApiService.addToCollection(id)
        Log.d("CCI_COLLECTION", "addToCollection id=$id code=${resp.code()}")
        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code()}")
        purchasedCapsLocalStore.add(id.toLong())
        _collectionChanged.tryEmit(Unit)
    }

    suspend fun removeFromCollection(id: Int) {
        val resp = capApiService.removeFromCollection(id)
        Log.d("CCI_COLLECTION", "removeFromCollection id=$id code=${resp.code()}")
        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code()}")
        purchasedCapsLocalStore.remove(id.toLong())
        _collectionChanged.tryEmit(Unit)
    }

    // Zmiana statusu na "Zakupione" bez odpięcia z kolekcji (np. z W kolekcji) nie woła
    // addToCollection — kapsel już tam jest po stronie API. Trzeba jednak zaktualizować
    // lokalny magazyn "zakupionych", od którego zależy zakładka Zakupione.
    fun markPurchasedLocally(id: Int) {
        purchasedCapsLocalStore.add(id.toLong())
        _collectionChanged.tryEmit(Unit)
    }

}

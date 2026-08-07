package pl.sroki.cci.android.ui.statistics.verification

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.model.binder.CachedCap
import javax.inject.Inject
import pl.sroki.cci.android.model.binder.CatalogStatus

/**
 * @param failed skan skończył się, nie dotknąwszy katalogu ani razu (offline, 403, bramka
 *   Cloudflare). Werdykt „Brak rozjazdów" byłby wtedy kłamstwem — nic nie zostało sprawdzone.
 */
data class ScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Boolean = false
)

@HiltViewModel
class CollectionVerificationViewModel @Inject constructor(
    private val capCacheRepository: CapCacheRepository,
    private val capPositionRepository: CapPositionRepository,
    private val collectionVerifier: CollectionVerifier,
) : ViewModel() {

    val flaggedCaps: StateFlow<List<CachedCap>> = capCacheRepository.flaggedCapsFlow()
        // WhileSubscribed: Flow chodzi po dwóch tabelach i przelicza się po każdej zmianie,
        // więc nie ma powodu trzymać go przy życiu, gdy ekranu nie widać.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    @Volatile
    private var cancelRequested = false

    fun runFullScan() {
        if (_scan.value.running) return
        cancelRequested = false
        // Flaga podnoszona synchronicznie, przed launch. Ustawiana w środku korutyny przepuszczała
        // dwa szybkie kliknięcia: obie widziały running == false i ruszały równoległe skany całej
        // kolekcji — podwójny ruch do crowncaps (szybciej budzi bramkę Cloudflare), dwa onProgress
        // depczące sobie stan, a pierwszy kończący oddawał przycisk w trakcie trwania drugiego.
        _scan.value = ScanState(running = true)
        viewModelScope.launch {
            // Zwykłe runCatching łapie Throwable, więc zjadało by CancellationException
            // rzucone przez anulowaną korutynę — psując kooperatywne anulowanie skanu, tak jak
            // w CollectionVerifier przed poprawką (patrz runCatchingCancellable tamże).
            val outcome = try {
                collectionVerifier.runFullScan(
                    onProgress = { done, total -> _scan.value = ScanState(true, done, total) },
                    isCancelled = { cancelRequested },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // ScanOutcome.healthy odróżnia „katalog odpowiadał" od „wszystko padło" — wcześniej
            // wynik był wyrzucany, więc skan przy leżącej sieci kończył się dokładnie tak samo
            // jak udany i pokazywał „Brak rozjazdów". Ten sam błąd naprawiono już w HomeViewModel.
            // Anulowanie to nie awaria: przerwany skan też nie dotknie katalogu.
            _scan.value = ScanState(
                running = false,
                failed = !cancelRequested && outcome?.healthy != true
            )
        }
    }

    fun cancelScan() { cancelRequested = true }

    /** Zachowaj mój snapshot — oznacz rozjazd jako rozstrzygnięty. */
    fun keep(capId: Long) {
        viewModelScope.launch {
            capCacheRepository.markVerified(capId, CatalogStatus.OK, System.currentTimeMillis())
        }
    }

    /** Odepnij kapsel z klasera — zostaje w kolekcji, wraca na listę Zakupione. */
    fun unlink(capId: Long) {
        viewModelScope.launch {
            runCatching {
                capPositionRepository.unassignToPurchased(capId)
                // Rozjazd rozstrzygnięty odpięciem — bez tego status zostaje w Roomie i wraca
                // w szczegółach kapsla jako baner, który nie ma już czego odpiąć.
                capCacheRepository.markVerified(capId, CatalogStatus.OK, System.currentTimeMillis())
            }.onFailure {
                // Bez tego nieudane odpięcie (np. brak sieci) wyglądało jak brak reakcji:
                // wiersz zostawał na liście, a użytkownik nie wiedział dlaczego.
                Log.w("CCI_SYNC", "nie udało się odpiąć kapsla $capId", it)
                Sentry.captureException(it)
            }
        }
    }
}

package pl.sroki.cci.android.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.sroki.cci.android.BuildConfig
import pl.sroki.cci.android.data.AuthRepository
import pl.sroki.cci.android.data.CapCacheRepository
import pl.sroki.cci.android.data.CollectionVerifier
import pl.sroki.cci.android.data.FirestoreRestoreUseCase
import pl.sroki.cci.android.data.RestoreResult
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.VerificationPrefs
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val userName: String? = null
)

sealed interface HomeEvent {
    data class ShowSnackbar(val message: String) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val authRepository: AuthRepository,
    private val firestoreRestoreUseCase: FirestoreRestoreUseCase,
    private val collectionVerifier: CollectionVerifier,
    private val verificationPrefs: VerificationPrefs,
    capCacheRepository: CapCacheRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        sessionRepository.isLoggedIn,
        sessionRepository.userName
    ) { isLoggedIn, userName ->
        HomeUiState(isLoggedIn = isLoggedIn, userName = userName)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState()
    )

    // Odznaka „X do przejrzenia" — liczba wstawionych kapsli z rozjazdem.
    val flaggedCount: StateFlow<Int> = capCacheRepository.flaggedCountFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        // Jednorazowy backfill snapshotu/fingerprintu po aktualizacji (ustanawia baseline 4126 kapsli).
        if (verificationPrefs.backfilledVersion < BuildConfig.VERSION_CODE) {
            viewModelScope.launch {
                val outcome = runCatching { collectionVerifier.runFullScan() }.getOrNull()
                // Wersję oznaczamy jako zbackfillowaną TYLKO po skanie, który dotarł do katalogu.
                // Gdy sieć leży albo Cloudflare blokuje, wszystkie verify() padają, runFullScan
                // kończy się „normalnie", a wcześniej i tak zapisywaliśmy wersję — baseline nigdy
                // się nie ustanawiał i nie było ponowienia. Teraz nieudany skan zostawia wersję
                // niezaznaczoną, więc backfill dokończy się przy następnym wejściu na ekran.
                if (outcome?.healthy == true) {
                    verificationPrefs.backfilledVersion = BuildConfig.VERSION_CODE
                }
            }
        }
    }

    var isSyncDialogOpen by mutableStateOf(false)
        private set
    var isSyncing by mutableStateOf(false)
        private set

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    fun requestSync() { isSyncDialogOpen = true }
    fun dismissSync() { isSyncDialogOpen = false }

    fun confirmSync() {
        isSyncDialogOpen = false
        isSyncing = true
        viewModelScope.launch {
            try {
                val message = when (val result = firestoreRestoreUseCase.restoreFromFirestore()) {
                    is RestoreResult.Success -> buildString {
                        append("Zsynchronizowano: ${result.binders} klaserów, ")
                        append("${result.pages} stron, ${result.caps} kapsli")
                        // Pominięta pozycja = kapsel, który wypadł z kolekcji. Milczenie w tym
                        // miejscu sprawiło, że wcześniejsza utrata wyszła na jaw dopiero
                        // przy ręcznym liczeniu kapsli.
                        if (result.skipped.isNotEmpty()) {
                            append(". UWAGA: ${result.skipped.size} pozycji pominięto ")
                            append("(zajęty slot): ${result.skipped.joinToString { it.capId.toString() }}")
                        }
                    }
                    RestoreResult.Empty ->
                        "Firestore nie zawiera danych — nic nie zmieniono"
                    RestoreResult.NotLoggedIn ->
                        "Nie zalogowano — zaloguj się ponownie"
                }
                _events.send(HomeEvent.ShowSnackbar(message))
            } catch (e: Exception) {
                _events.send(HomeEvent.ShowSnackbar("Błąd synchronizacji — lokalne dane bez zmian"))
            } finally {
                isSyncing = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}

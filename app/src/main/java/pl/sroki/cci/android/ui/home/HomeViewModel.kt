package pl.sroki.cci.android.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.AuthRepository
import pl.sroki.cci.android.data.FirestoreRestoreUseCase
import pl.sroki.cci.android.data.SessionRepository
import javax.inject.Inject

sealed interface MigrationState {
    data object Idle : MigrationState
    data object Loading : MigrationState
    data class Success(val count: Int) : MigrationState
    data class Error(val message: String) : MigrationState
}

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val userName: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val authRepository: AuthRepository,
    private val firestoreRestoreUseCase: FirestoreRestoreUseCase
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

    var migrationState: MigrationState by mutableStateOf(MigrationState.Idle)
        private set

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun migrateFromUid(oldUid: String) {
        if (migrationState is MigrationState.Loading) return
        migrationState = MigrationState.Loading
        viewModelScope.launch {
            migrationState = try {
                val count = firestoreRestoreUseCase.migrateFromUid(oldUid.trim())
                MigrationState.Success(count)
            } catch (e: Exception) {
                MigrationState.Error(e.message ?: "Błąd migracji")
            }
        }
    }

    fun resetMigration() { migrationState = MigrationState.Idle }
}

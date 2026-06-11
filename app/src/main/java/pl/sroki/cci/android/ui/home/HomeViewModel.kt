package pl.sroki.cci.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import pl.sroki.cci.android.data.SessionRepository
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val userName: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
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
}

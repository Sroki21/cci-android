package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.model.CapExtended
import java.io.IOException
import javax.inject.Inject

sealed interface CapDetailUiState {
    data class Success(val cap: CapExtended) : CapDetailUiState
    object Error : CapDetailUiState
    object Loading : CapDetailUiState
}

@HiltViewModel
class CapDetailViewModel @Inject constructor(private val repository: CapsRepository) :
    ViewModel() {

    var capDetailUiState: CapDetailUiState by mutableStateOf(CapDetailUiState.Loading)
        private set

    fun getCap(id: Int) {
        viewModelScope.launch {
            capDetailUiState = try {
                val listResult = repository.getById(id)
                CapDetailUiState.Success(listResult)
            } catch (e: IOException) {
                CapDetailUiState.Error
            }
        }
    }

}
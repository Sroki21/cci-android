package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CapPositionRepository
import pl.sroki.cci.android.data.CapsRepository
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.model.CapExtended
import java.io.IOException
import javax.inject.Inject

enum class CapStatus { IN_COLLECTION, PURCHASED, MISSING }

sealed interface CapDetailUiState {
    data class Success(
        val cap: CapExtended,
        val status: CapStatus,
        val binderInfo: CapBinderInfo?
    ) : CapDetailUiState
    object Error : CapDetailUiState
    object Loading : CapDetailUiState
}

@HiltViewModel
class CapDetailViewModel @Inject constructor(
    private val repository: CapsRepository,
    private val capPositionRepository: CapPositionRepository
) : ViewModel() {

    var capDetailUiState: CapDetailUiState by mutableStateOf(CapDetailUiState.Loading)
        private set

    fun getCap(id: Int) {
        viewModelScope.launch {
            capDetailUiState = try {
                val cap = repository.getById(id)
                val binderInfo = capPositionRepository.getBinderInfoByCapId(id.toLong())
                val status = when {
                    binderInfo != null -> CapStatus.IN_COLLECTION
                    cap.isInCollection -> CapStatus.PURCHASED
                    else -> CapStatus.MISSING
                }
                CapDetailUiState.Success(cap = cap, status = status, binderInfo = binderInfo)
            } catch (e: IOException) {
                CapDetailUiState.Error
            }
        }
    }
}

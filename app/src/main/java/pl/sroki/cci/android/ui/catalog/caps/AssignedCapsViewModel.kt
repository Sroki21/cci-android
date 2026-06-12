package pl.sroki.cci.android.ui.catalog.caps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import pl.sroki.cci.android.data.CapPositionRepository
import javax.inject.Inject

@HiltViewModel
class AssignedCapsViewModel @Inject constructor(
    capPositionRepository: CapPositionRepository
) : ViewModel() {

    val assignedCapIds: StateFlow<Set<Long>> = capPositionRepository.getAllCapIdsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
}

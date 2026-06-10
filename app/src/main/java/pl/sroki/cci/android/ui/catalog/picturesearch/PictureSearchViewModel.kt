package pl.sroki.cci.android.ui.catalog.picturesearch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.CategoriesRepository
import pl.sroki.cci.android.model.Category
import java.io.IOException
import javax.inject.Inject

sealed interface PictureSearchUiState {
    data class Success(val categories: List<Category>) : PictureSearchUiState
    data class Error(val error: Throwable) : PictureSearchUiState
    object Loading : PictureSearchUiState
}

@HiltViewModel
class PictureSearchViewModel @Inject constructor(private val repository: CategoriesRepository) :
    ViewModel() {
    /** The mutable State that stores the status of the most recent request */
    var pictureSearchUiState: PictureSearchUiState by mutableStateOf(PictureSearchUiState.Loading)
        private set

    var selectedCategories: Set<Category> by mutableStateOf(setOf())

    fun toggleCategory(category: Category) {
        selectedCategories = if (selectedCategories.contains(category)) {
            selectedCategories.minus(category)
        } else {
            selectedCategories.plus(category)
        }
    }

    fun getCategories() {
        viewModelScope.launch {
            pictureSearchUiState = try {
                val listResult = repository.getCategories()
                PictureSearchUiState.Success(listResult)
            } catch (e: IOException) {
                PictureSearchUiState.Error(e)
            }
        }
    }
}

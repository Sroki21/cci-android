package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.ui.components.FullSizeLoader

@Composable
fun CapDetailScreen(
    id: Int,
    onBack: () -> Unit,
) {
    val viewModel = hiltViewModel<CapDetailViewModel>()

    LaunchedEffect(true) {
        viewModel.getCap(id)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = "#$id") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
    }) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (val state = viewModel.capDetailUiState) {
                is CapDetailUiState.Error -> Text("Error")
                is CapDetailUiState.Loading -> FullSizeLoader()
                is CapDetailUiState.Success -> CapDetailView(cap = state.cap)
            }
        }
    }
}

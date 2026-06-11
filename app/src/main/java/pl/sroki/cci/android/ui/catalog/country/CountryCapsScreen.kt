package pl.sroki.cci.android.ui.catalog.country

import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.ui.catalog.caps.CapsView

@Composable
fun CountryCapsScreen(
    id: Int,
    name: String,
    onBack: () -> Unit,
    onCapClick: (Cap) -> Unit,
) {
    val viewModel = hiltViewModel<CountryCapsViewModel>()
    viewModel.id = id

    val caps = viewModel.caps.collectAsLazyPagingItems()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = name) },
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
        CapsView(caps = caps, onCapClick = onCapClick)
    }
}

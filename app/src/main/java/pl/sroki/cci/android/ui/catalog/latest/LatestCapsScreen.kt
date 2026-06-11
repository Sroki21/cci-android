package pl.sroki.cci.android.ui.catalog.latest

import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.ui.catalog.caps.CapsView

@Composable
fun LatestCapsScreen(
    onBack: () -> Unit,
    onCapClick: (Cap) -> Unit,
) {
    val viewModel = hiltViewModel<LatestCapsViewModel>()
    val caps = viewModel.caps.collectAsLazyPagingItems()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = "Additions") },
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

package pl.sroki.cci.android.ui.catalog.caps.quicksearch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.ui.catalog.caps.CapsView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSearchScreen(
    query: String,
    onBack: () -> Unit,
    onCapClick: (Cap) -> Unit,
) {
    val viewModel = hiltViewModel<QuickSearchViewModel>()
    viewModel.query = query

    val caps = viewModel.caps.collectAsLazyPagingItems()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = query) },
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
            CapsView(caps = caps, onCapClick = onCapClick)
        }
    }
}

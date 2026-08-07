package pl.sroki.cci.android.ui.catalog.latest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
        Box(Modifier.padding(innerPadding)) {
            CapsView(caps = caps, onCapClick = onCapClick)
        }
    }
}

package pl.sroki.cci.android.ui.statistics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.ui.components.FullSizeLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountriesListScreen(
    onBack: () -> Unit,
    onCountryClick: (String) -> Unit,
) {
    val viewModel = hiltViewModel<CountriesListViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Kraje") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Wstecz"
                    )
                }
            }
        )
    }) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading -> FullSizeLoader()
                uiState.countries.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { Text("Brak krajów") }
                else -> LazyColumn {
                    items(uiState.countries, key = { it.name }) { stat ->
                        CountryRow(stat, onClick = { onCountryClick(stat.name) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

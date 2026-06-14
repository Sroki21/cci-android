package pl.sroki.cci.android.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import pl.sroki.cci.android.ui.components.FullSizeLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    onOpenCountries: () -> Unit = {},
    onOpenLocations: () -> Unit = {},
    onCountryClick: (String) -> Unit = {}
) {
    val viewModel = hiltViewModel<StatisticsViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Statystyki") },
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
            if (uiState is StatisticsUiState.Success && (uiState as StatisticsUiState.Success).isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (val state = uiState) {
                is StatisticsUiState.Loading -> FullSizeLoader()
                is StatisticsUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.load() }) { Text("Spróbuj ponownie") }
                    }
                }
                is StatisticsUiState.Success -> {
                    LazyColumn {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatCard(label = "Kapsle", value = state.totalCaps.toString(), modifier = Modifier.weight(1f))
                                StatCard(label = "Kraje", value = state.totalCountries.toString(), onClick = onOpenCountries, modifier = Modifier.weight(1f))
                            }
                        }
                        item {
                            ListItem(
                                modifier = Modifier.clickable(onClick = onOpenLocations),
                                headlineContent = {
                                    Text("Lokalizacje", style = MaterialTheme.typography.titleMedium)
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null
                                    )
                                }
                            )
                            HorizontalDivider()
                        }
                        item {
                            Text(
                                text = "Top 5 krajów",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(state.topCountries, key = { it.name }) { stat ->
                            CountryRow(stat, onClick = { onCountryClick(stat.name) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CountryRow(stat: CountryStat, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (stat.flagUrl != null) {
                AsyncImage(
                    model = stat.flagUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 36.dp, height = 24.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            } else {
                Box(Modifier.size(width = 36.dp, height = 24.dp))
            }
        },
        headlineContent = { Text(stat.name) },
        trailingContent = {
            Text(
                text = stat.count.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

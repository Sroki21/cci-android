package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.ui.components.FullSizeLoader

@Composable
private fun DriftBanner(
    status: String?,
    changes: List<Pair<String, String>>,
    onKeep: () -> Unit,
    onAccept: () -> Unit,
    onUnlink: () -> Unit,
) {
    val message = when (status) {
        "swapped" -> "Uwaga: pod tym ID jest teraz inny kapsel niż zapisany."
        "updated" -> "Ten kapsel zmienił się w katalogu."
        "missing" -> "Ten kapsel zniknął z katalogu."
        "producer_removed" -> "Wybrany producent/kraj już nie istnieje w katalogu — wybierz ponownie w polu \"Kraj\"."
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            if (status == "updated" && changes.isNotEmpty()) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(top = 4.dp),
                ) {
                    changes.forEach { (label, diff) ->
                        Text(
                            text = "$label: $diff",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = onKeep) { Text("Zachowaj") }
                if (status != "missing") {
                    TextButton(onClick = onAccept) { Text("Zaakceptuj nowy") }
                }
                TextButton(onClick = onUnlink) { Text("Odepnij") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapDetailScreen(
    id: Int,
    onBack: () -> Unit,
    onProducerClick: (String) -> Unit = {},
) {
    val viewModel = hiltViewModel<CapDetailViewModel>()
    val uiState = viewModel.capDetailUiState
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    var statusMenuExpanded by remember { mutableStateOf(false) }
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(true) {
        viewModel.getCap(id)
    }

    LaunchedEffect(viewModel.assignmentError) {
        val err = viewModel.assignmentError ?: return@LaunchedEffect
        snackbarState.showSnackbar(err)
        viewModel.dismissError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "$id")
                        Spacer(Modifier.weight(1f))
                        if (uiState is CapDetailUiState.Success && isLoggedIn) {
                            val (label, color) = when (uiState.status) {
                                CapStatus.IN_COLLECTION -> "W kolekcji" to Color(0xFF00FF00)
                                CapStatus.PURCHASED -> "Zakupiony" to Color(0xFF2196F3)
                                CapStatus.MISSING -> "Brak" to Color(0xFFF44336)
                            }
                            Box {
                                Text(
                                    text = label,
                                    color = color,
                                    modifier = Modifier.clickable { statusMenuExpanded = true }
                                )
                                DropdownMenu(
                                    expanded = statusMenuExpanded,
                                    onDismissRequest = { statusMenuExpanded = false }
                                ) {
                                    if (uiState.status != CapStatus.PURCHASED) {
                                        DropdownMenuItem(
                                            text = { Text("Zakupiony", color = Color(0xFF2196F3)) },
                                            onClick = {
                                                viewModel.setStatus(CapStatus.PURCHASED)
                                                statusMenuExpanded = false
                                            }
                                        )
                                    }
                                    if (uiState.status != CapStatus.MISSING) {
                                        DropdownMenuItem(
                                            text = { Text("Brak", color = Color(0xFFF44336)) },
                                            onClick = {
                                                viewModel.setStatus(CapStatus.MISSING)
                                                statusMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Wstecz"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (uiState) {
                is CapDetailUiState.Error -> Text("Błąd ładowania")
                is CapDetailUiState.Loading -> FullSizeLoader()
                is CapDetailUiState.Success -> androidx.compose.foundation.layout.Column {
                    DriftBanner(
                        status = viewModel.catalogStatus,
                        changes = viewModel.catalogChanges,
                        onKeep = viewModel::keepSnapshot,
                        onAccept = viewModel::acceptNew,
                        onUnlink = viewModel::unlinkFlagged,
                    )
                    CapDetailView(
                        cap = uiState.cap,
                        status = uiState.status,
                        binderInfo = uiState.binderInfo,
                        binders = if (isLoggedIn) viewModel.binders else emptyList(),
                        binderPages = viewModel.binderPages,
                        selectedBinderId = viewModel.selectedBinderId,
                        selectedPageId = viewModel.selectedPageId,
                        selectedPosition = viewModel.selectedPosition,
                        isSaving = viewModel.isSaving,
                        binderSuggestion = viewModel.binderSuggestion,
                        onBinderSelected = viewModel::onBinderSelected,
                        onPageSelected = viewModel::onPageSelected,
                        onPositionSelected = viewModel::onPositionSelected,
                        onProducerClick = onProducerClick,
                        onProducerSelected = viewModel::selectProducer,
                    )
                }
            }
        }
    }
}

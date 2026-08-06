package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.ui.components.FullSizeLoader
import pl.sroki.cci.android.ui.theme.StatusInCollection
import pl.sroki.cci.android.ui.theme.StatusMissing
import pl.sroki.cci.android.ui.theme.StatusPurchased
import pl.sroki.cci.android.model.binder.CatalogStatus

@Composable
private fun DriftBanner(
    status: CatalogStatus?,
    changes: List<Pair<String, String>>,
    onKeep: () -> Unit,
    onAccept: () -> Unit,
    onUnlink: () -> Unit,
) {
    // Wyczerpujacy when — nowy status w CatalogStatus wymusi decyzje, czy pokazac tu baner.
    val message = when (status) {
        CatalogStatus.SWAPPED -> "Uwaga: pod tym ID jest teraz inny kapsel niż zapisany."
        CatalogStatus.UPDATED -> "Ten kapsel zmienił się w katalogu."
        CatalogStatus.MISSING -> "Ten kapsel zniknął z katalogu."
        CatalogStatus.PRODUCER_REMOVED ->
            "Wybrany producent/kraj już nie istnieje w katalogu — wybierz ponownie w polu \"Kraj\"."
        CatalogStatus.OK, CatalogStatus.UNKNOWN, null -> return
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
            if (status == CatalogStatus.UPDATED && changes.isNotEmpty()) {
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
                // PRODUCER_REMOVED wynika z wiszącego selectedProducerId, którego ani „Zachowaj",
                // ani „Zaakceptuj nowy" nie ruszają — oba tylko ustawiłyby OK w Roomie, a następny
                // skan kolekcji znów by oflagował. Trwale rozwiązuje to wybór producenta w polu
                // „Kraj" (zgodnie z treścią banera) albo „Odepnij"; te dwa przyciski chowamy.
                if (status != CatalogStatus.PRODUCER_REMOVED) {
                    TextButton(onClick = onKeep) { Text("Zachowaj") }
                }
                // Kapsla usuniętego z katalogu nie da się "zaakceptować" — nie ma czego pobrać.
                if (status != CatalogStatus.MISSING && status != CatalogStatus.PRODUCER_REMOVED) {
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
    onCapNumberClick: (Long) -> Unit = {},
) {
    val viewModel = hiltViewModel<CapDetailViewModel>()
    val uiState = viewModel.capDetailUiState
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    var statusMenuExpanded by remember { mutableStateOf(false) }
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(id) {
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
                                CapStatus.IN_COLLECTION -> "W kolekcji" to StatusInCollection
                                CapStatus.PURCHASED -> "Zakupiony" to StatusPurchased
                                CapStatus.MISSING -> "Brak" to StatusMissing
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
                                            text = { Text("Zakupiony", color = StatusPurchased) },
                                            onClick = {
                                                viewModel.setStatus(CapStatus.PURCHASED)
                                                statusMenuExpanded = false
                                            }
                                        )
                                    }
                                    if (uiState.status != CapStatus.MISSING) {
                                        DropdownMenuItem(
                                            text = { Text("Brak", color = StatusMissing) },
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
                is CapDetailUiState.Error -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Nie udało się załadować kapsla")
                    TextButton(onClick = { viewModel.getCap(id) }) { Text("Spróbuj ponownie") }
                }
                is CapDetailUiState.Loading -> FullSizeLoader()
                is CapDetailUiState.Success -> Column {
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
                        onCapNumberClick = onCapNumberClick,
                    )
                }
            }
        }
    }
}

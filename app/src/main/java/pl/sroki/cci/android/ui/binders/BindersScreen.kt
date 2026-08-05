package pl.sroki.cci.android.ui.binders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.Cap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindersScreen(
    onBack: () -> Unit,
    onCapClick: (Long) -> Unit,
) {
    val vm = hiltViewModel<BindersViewModel>()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is BindersEvent.ShowSnackbar -> snackbarState.showSnackbar(event.message)
            }
        }
    }

    val selectedCountry = vm.selectedCountryName
    val filteredBinders = if (selectedCountry.isEmpty()) uiState.binders
    else uiState.binders.filter { binder ->
        val pages = uiState.binderPages[binder.id] ?: emptyList()
        pages.any { page ->
            (uiState.capPositions[page.id] ?: emptyList()).any { pos ->
                uiState.capInfo[pos.capId]?.country == selectedCountry
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Klasery") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            item {
                CountryFilterRow(
                    countryName = selectedCountry,
                    countries = vm.countries,
                    onCountrySelected = vm::setCountry
                )
            }
            items(filteredBinders, key = { it.id }) { binder ->
                val expanded = binder.id in uiState.expandedBinderIds
                val pages = uiState.binderPages[binder.id] ?: emptyList()
                val filteredPages = if (selectedCountry.isEmpty()) pages
                else pages.filter { page ->
                    (uiState.capPositions[page.id] ?: emptyList()).any { pos ->
                        uiState.capInfo[pos.capId]?.country == selectedCountry
                    }
                }
                val totalCaps = filteredPages.sumOf { page ->
                    val positions = uiState.capPositions[page.id] ?: emptyList()
                    if (selectedCountry.isEmpty()) positions.size
                    else positions.count { pos -> uiState.capInfo[pos.capId]?.country == selectedCountry }
                }
                ExpandableBinderRow(
                    binder = binder,
                    expanded = expanded,
                    pages = filteredPages,
                    expandedPageIds = uiState.expandedPageIds,
                    capPositions = uiState.capPositions,
                    capInfo = uiState.capInfo,
                    capStatus = uiState.capStatus,
                    totalCaps = totalCaps,
                    selectedCountry = selectedCountry,
                    isLoading = uiState.isLoading,
                    onToggle = { vm.toggleExpand(binder.id) },
                    onTogglePage = { vm.togglePageExpand(it) },
                    onDeleteBinder = { vm.requestDeleteBinder(binder.id) },
                    onAddPage = { vm.addPage(binder.id) },
                    onDeletePage = { vm.requestDeletePage(it) },
                    onRenamePage = { vm.requestRenamePage(it) },
                    onMovePage = { vm.requestMovePage(it) },
                    onCapClick = onCapClick
                )
            }
            item {
                Button(
                    onClick = vm::showCreateDialog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Dodaj klaser")
                }
            }
        }
    }

    if (uiState.isCreateDialogOpen) {
        CreateBinderDialog(
            onDismiss = vm::dismissCreateDialog,
            onCreate = vm::createBinder,
            isLoading = uiState.isLoading
        )
    }

    if (uiState.deleteBinderConfirmId != null) {
        AlertDialog(
            onDismissRequest = vm::dismissDeleteBinder,
            title = { Text("Usuń klaser?") },
            text = { Text("Tej operacji nie można cofnąć.") },
            confirmButton = {
                TextButton(onClick = vm::confirmDeleteBinder) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDeleteBinder) { Text("Anuluj") }
            }
        )
    }

    if (uiState.deletePageConfirmId != null) {
        AlertDialog(
            onDismissRequest = vm::dismissDeletePage,
            title = { Text("Usuń stronę?") },
            text = { Text("Tej operacji nie można cofnąć.") },
            confirmButton = {
                TextButton(onClick = vm::confirmDeletePage) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDeletePage) { Text("Anuluj") }
            }
        )
    }

    val renamePageTargetId = uiState.renamePageTargetId
    if (renamePageTargetId != null) {
        val currentPageNumber = uiState.binderPages.values
            .flatten()
            .firstOrNull { it.id == renamePageTargetId }
            ?.pageNumber
            ?: 1
        RenamePageDialog(
            currentPageNumber = currentPageNumber,
            onDismiss = vm::dismissRenamePage,
            onConfirm = vm::confirmRenamePage
        )
    }

    val movePageTargetId = uiState.movePageTargetId
    if (movePageTargetId != null) {
        val currentBinderId = uiState.binderPages.entries
            .firstOrNull { (_, pages) -> pages.any { it.id == movePageTargetId } }
            ?.key
        MovePageDialog(
            binders = uiState.binders,
            currentBinderId = currentBinderId,
            onDismiss = vm::dismissMovePage,
            onConfirm = vm::confirmMovePage
        )
    }
}

@Composable
private fun CountryFilterRow(
    countryName: String,
    countries: List<Country>,
    onCountrySelected: (Country?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogSearch by remember { mutableStateOf("") }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) showDialog = true
        }
    }

    OutlinedTextField(
        value = countryName,
        onValueChange = {},
        placeholder = { Text("Kraj") },
        readOnly = true,
        singleLine = true,
        interactionSource = interactionSource,
        trailingIcon = {
            if (countryName.isNotEmpty()) {
                IconButton(onClick = { onCountrySelected(null) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Wyczyść kraj")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )

    if (showDialog) {
        val filtered = remember(dialogSearch, countries) {
            if (dialogSearch.isBlank()) countries
            else countries.filter { it.name.contains(dialogSearch, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialogSearch = "" },
            title = { Text("Wybierz kraj") },
            text = {
                Column {
                    OutlinedTextField(
                        value = dialogSearch,
                        onValueChange = { dialogSearch = it },
                        label = { Text("Szukaj") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(filtered, key = { it.id }) { country ->
                            ListItem(
                                headlineContent = { Text(country.name) },
                                modifier = Modifier.clickable {
                                    onCountrySelected(country)
                                    showDialog = false
                                    dialogSearch = ""
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandableBinderRow(
    binder: Binder,
    expanded: Boolean,
    pages: List<BinderPage>,
    expandedPageIds: Set<Long>,
    capPositions: Map<Long, List<CapPosition>>,
    capInfo: Map<Long, Cap>,
    capStatus: Map<Long, String>,
    totalCaps: Int,
    selectedCountry: String,
    isLoading: Boolean,
    onToggle: () -> Unit,
    onTogglePage: (Long) -> Unit,
    onDeleteBinder: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (Long) -> Unit,
    onRenamePage: (Long) -> Unit,
    onMovePage: (Long) -> Unit,
    onCapClick: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val binderFlagged = pages.any { page ->
            (capPositions[page.id] ?: emptyList()).any { isFlagged(capStatus[it.capId]) }
        }
        var showBinderMenu by remember { mutableStateOf(false) }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onToggle,
                        onLongClick = { showBinderMenu = true }
                    )
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val binderLabel = if (totalCaps > 0) "${binder.name} - $totalCaps" else binder.name
                Text(
                    text = binderLabel,
                    modifier = Modifier.weight(1f),
                    color = if (binderFlagged) MaterialTheme.colorScheme.error else Color.Unspecified,
                    fontWeight = if (binderFlagged) FontWeight.Bold else null
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Zwiń" else "Rozwiń"
                    )
                }
            }
            DropdownMenu(
                expanded = showBinderMenu,
                onDismissRequest = { showBinderMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Usuń klaser") },
                    onClick = {
                        showBinderMenu = false
                        onDeleteBinder()
                    }
                )
            }
        }
        if (expanded) {
            pages.forEach { page ->
                key(page.id) {
                    val pageExpanded = page.id in expandedPageIds
                    val allCaps = capPositions[page.id]?.sortedBy { it.position } ?: emptyList()
                    val caps = if (selectedCountry.isEmpty()) allCaps
                    else allCaps.filter { capInfo[it.capId]?.country == selectedCountry }
                    val countries = caps.mapNotNull { capInfo[it.capId]?.country }.distinct().sorted()
                    val countriesSuffix = if (countries.isEmpty()) "" else " (${countries.joinToString(", ")})"
                    val pageLabel = "Strona ${page.pageNumber}$countriesSuffix${if (caps.isNotEmpty()) " - ${caps.size}" else ""}"
                    val pageFlagged = allCaps.any { isFlagged(capStatus[it.capId]) }
                    var showPageMenu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onTogglePage(page.id) },
                                    onLongClick = { showPageMenu = true }
                                )
                                .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pageLabel,
                                modifier = Modifier.weight(1f),
                                color = if (pageFlagged) MaterialTheme.colorScheme.error else Color.Unspecified,
                                fontWeight = if (pageFlagged) FontWeight.Bold else null
                            )
                            IconButton(onClick = { onTogglePage(page.id) }) {
                                Icon(
                                    imageVector = if (pageExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (pageExpanded) "Zwiń stronę" else "Rozwiń stronę"
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showPageMenu,
                            onDismissRequest = { showPageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Zmień numer strony") },
                                onClick = {
                                    showPageMenu = false
                                    onRenamePage(page.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Zmień klaser") },
                                onClick = {
                                    showPageMenu = false
                                    onMovePage(page.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Usuń stronę") },
                                onClick = {
                                    showPageMenu = false
                                    onDeletePage(page.id)
                                }
                            )
                        }
                    }
                    if (pageExpanded) {
                        caps.forEach { cap ->
                            val info = capInfo[cap.capId]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCapClick(cap.capId) }
                                    .padding(start = 48.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = info?.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                )
                                val capFlagged = isFlagged(capStatus[cap.capId])
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = "Pozycja ${cap.position}: ${cap.capId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (capFlagged) MaterialTheme.colorScheme.error else Color.Unspecified,
                                        fontWeight = if (capFlagged) FontWeight.Bold else null
                                    )
                                    if (info != null) {
                                        Text(
                                            text = info.country,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = onAddPage,
                enabled = !isLoading,
                modifier = Modifier.padding(start = 24.dp)
            ) {
                Text("Dodaj stronę")
            }
        }
    }
}

private fun isFlagged(status: String?): Boolean =
    status == "missing" || status == "swapped" || status == "updated"

@Composable
private fun CreateBinderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    isLoading: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowy klaser") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nazwa") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank() && !isLoading
            ) { Text("Zapisz") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}

@Composable
private fun MovePageDialog(
    binders: List<Binder>,
    currentBinderId: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val targets = binders.filter { it.id != currentBinderId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zmień klaser") },
        text = {
            if (targets.isEmpty()) {
                Text("Brak innych klaserów do wyboru.")
            } else {
                LazyColumn {
                    items(targets, key = { it.id }) { binder ->
                        ListItem(
                            headlineContent = { Text(binder.name) },
                            modifier = Modifier.clickable { onConfirm(binder.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}

@Composable
private fun RenamePageDialog(
    currentPageNumber: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var numberText by remember { mutableStateOf(currentPageNumber.toString()) }
    val newNumber = numberText.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zmień numer strony") },
        text = {
            OutlinedTextField(
                value = numberText,
                onValueChange = { numberText = it },
                label = { Text("Numer strony") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (newNumber != null) onConfirm(newNumber) },
                enabled = newNumber != null && newNumber >= 1
            ) { Text("Zapisz") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}

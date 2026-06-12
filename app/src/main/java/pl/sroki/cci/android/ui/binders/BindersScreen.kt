package pl.sroki.cci.android.ui.binders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.material3.MaterialTheme
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.model.Cap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindersScreen(onBack: () -> Unit) {
    val vm = hiltViewModel<BindersViewModel>()
    val uiState by vm.uiState.collectAsState()
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is BindersEvent.ShowSnackbar -> snackbarState.showSnackbar(event.message)
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
            items(uiState.binders, key = { it.id }) { binder ->
                val expanded = binder.id in uiState.expandedBinderIds
                val pages = uiState.binderPages[binder.id] ?: emptyList()
                val totalCaps = pages.sumOf { uiState.capPositions[it.id]?.size ?: 0 }
                ExpandableBinderRow(
                    binder = binder,
                    expanded = expanded,
                    pages = pages,
                    expandedPageIds = uiState.expandedPageIds,
                    capPositions = uiState.capPositions,
                    capInfo = uiState.capInfo,
                    totalCaps = totalCaps,
                    isLoading = uiState.isLoading,
                    onToggle = { vm.toggleExpand(binder.id) },
                    onTogglePage = { vm.togglePageExpand(it) },
                    onDeleteBinder = { vm.requestDeleteBinder(binder.id) },
                    onAddPage = { vm.addPage(binder.id) },
                    onDeletePage = { vm.requestDeletePage(it) }
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
}

@Composable
private fun ExpandableBinderRow(
    binder: Binder,
    expanded: Boolean,
    pages: List<BinderPage>,
    expandedPageIds: Set<Long>,
    capPositions: Map<Long, List<CapPosition>>,
    capInfo: Map<Long, Cap>,
    totalCaps: Int,
    isLoading: Boolean,
    onToggle: () -> Unit,
    onTogglePage: (Long) -> Unit,
    onDeleteBinder: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val binderLabel = if (totalCaps > 0) "${binder.name} - $totalCaps" else binder.name
            Text(text = binderLabel, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Zwiń" else "Rozwiń"
                )
            }
            IconButton(onClick = onDeleteBinder) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Usuń klaser")
            }
        }
        if (expanded) {
            pages.forEach { page ->
                val pageExpanded = page.id in expandedPageIds
                val caps = capPositions[page.id]?.sortedBy { it.position } ?: emptyList()
                val countries = caps.mapNotNull { capInfo[it.capId]?.country }.distinct().sorted()
                val countriesSuffix = if (countries.isEmpty()) "" else " (${countries.joinToString(", ")})"
                val pageCaps = caps.size
                val pageLabel = "Strona ${page.pageNumber}$countriesSuffix${if (pageCaps > 0) " - $pageCaps" else ""}"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pageLabel,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onTogglePage(page.id) }) {
                        Icon(
                            imageVector = if (pageExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (pageExpanded) "Zwiń stronę" else "Rozwiń stronę"
                        )
                    }
                    IconButton(onClick = { onDeletePage(page.id) }) {
                        Icon(imageVector = Icons.Filled.Delete, contentDescription = "Usuń stronę")
                    }
                }
                if (pageExpanded) {
                    caps.forEach { cap ->
                        val info = capInfo[cap.capId]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
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
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = "Pozycja ${cap.position}: ${cap.capId}",
                                    style = MaterialTheme.typography.bodySmall
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

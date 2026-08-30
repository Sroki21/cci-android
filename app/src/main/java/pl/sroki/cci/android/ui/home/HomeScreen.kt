package pl.sroki.cci.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.sroki.cci.android.NavigationItem
import pl.sroki.cci.android.R
import pl.sroki.cci.android.navigation.Screen
import pl.sroki.cci.android.ui.home.HomeEvent
import pl.sroki.cci.android.ui.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onClick: (Screen) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onLoginClick: () -> Unit = {}
) {
    val vm = hiltViewModel<HomeViewModel>()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val flaggedCount by vm.flaggedCount.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var logoutDialogOpen by remember { mutableStateOf(false) }
    val snackbarState = remember { SnackbarHostState() }

    // Puste/białe zapytanie nawigowało do wyników bez frazy — ignorujemy je.
    val doSearch = { if (query.isNotBlank()) onSearch(query) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is HomeEvent.ShowSnackbar -> snackbarState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
        TopAppBar(
            title = {
                Image(
                    painter = painterResource(id = R.drawable.ic_cci_logo),
                    contentDescription = "CCI",
                    modifier = Modifier.height(40.dp)
                )
            },
            actions = {
                if (uiState.isLoggedIn) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text(uiState.userName ?: "Konto")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (flaggedCount > 0) "Weryfikacja kolekcji ($flaggedCount)"
                                        else "Weryfikacja kolekcji"
                                    )
                                },
                                trailingIcon = {
                                    if (flaggedCount > 0) {
                                        Badge { Text("$flaggedCount") }
                                    }
                                },
                                onClick = {
                                    menuExpanded = false
                                    onClick(Screen.CollectionVerification)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Synchronizuj z Firestore") },
                                onClick = {
                                    menuExpanded = false
                                    vm.requestSync()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Wyloguj") },
                                onClick = {
                                    menuExpanded = false
                                    logoutDialogOpen = true
                                }
                            )
                        }
                    }
                } else {
                    TextButton(onClick = onLoginClick) {
                        Text("Zaloguj")
                    }
                }
            }
        )
    }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Szukaj") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = doSearch) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Szukaj")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            NavigationItem(text = "Szukaj wg zdjęcia", icon = Icons.Filled.CameraAlt) {
                onClick(Screen.PictureSearch)
            }
            NavigationItem(text = "Szukanie zaawansowane", icon = Icons.Filled.FilterList) {
                onClick(Screen.AdvancedSearch)
            }
            if (uiState.isLoggedIn) {
                NavigationItem(text = "Zakupione", icon = Icons.Filled.ShoppingCart) {
                    onClick(Screen.Purchased)
                }
                NavigationItem(text = "Statystyki", icon = Icons.Filled.BarChart) {
                    onClick(Screen.Statistics)
                }
                NavigationItem(text = "Klasery", icon = Icons.Filled.LibraryBooks) {
                    onClick(Screen.Binders)
                }
            }
        }
    }

    if (vm.isSyncDialogOpen) {
        AlertDialog(
            onDismissRequest = vm::dismissSync,
            title = { Text("Synchronizować z Firestore?") },
            text = { Text("Lokalne klasery zostaną zastąpione danymi z Firestore. Zmiany zapisane w chmurze (w tym poprawki) wejdą do aplikacji.") },
            confirmButton = {
                TextButton(onClick = vm::confirmSync) { Text("Synchronizuj") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissSync) { Text("Anuluj") }
            }
        )
    }

    if (vm.isSyncing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Synchronizacja...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Pobieram dane z Firestore")
                }
            }
        )
    }

    if (logoutDialogOpen) {
        AlertDialog(
            onDismissRequest = { logoutDialogOpen = false },
            title = { Text("Wylogować?") },
            confirmButton = {
                TextButton(onClick = {
                    logoutDialogOpen = false
                    vm.logout()
                }) { Text("Wyloguj") }
            },
            dismissButton = {
                TextButton(onClick = { logoutDialogOpen = false }) { Text("Anuluj") }
            }
        )
    }
}

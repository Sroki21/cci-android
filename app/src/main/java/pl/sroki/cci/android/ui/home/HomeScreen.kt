package pl.sroki.cci.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.sroki.cci.android.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.NavigationItem
import pl.sroki.cci.android.navigation.Screen
import pl.sroki.cci.android.ui.home.HomeViewModel
import pl.sroki.cci.android.ui.home.MigrationState
import pl.sroki.cci.android.ui.theme.CCITheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onClick: (Screen) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onLoginClick: () -> Unit = {}
) {
    val vm = hiltViewModel<HomeViewModel>()
    val uiState by vm.uiState.collectAsState()
    val migrationState = vm.migrationState

    var query by remember { mutableStateOf("") }
    var showMigrateDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
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
                                text = { Text("Migruj dane z UID") },
                                onClick = {
                                    menuExpanded = false
                                    showMigrateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Wyloguj") },
                                onClick = {
                                    menuExpanded = false
                                    vm.logout()
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
                    IconButton(onClick = { onSearch(query) }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Szukaj")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
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
            }
            if (uiState.isLoggedIn) {
                NavigationItem(text = "Statystyki", icon = Icons.Filled.BarChart) {
                    onClick(Screen.Statistics)
                }
            }
            if (uiState.isLoggedIn) {
                NavigationItem(text = "Klasery", icon = Icons.Filled.LibraryBooks) {
                    onClick(Screen.Binders)
                }
            }
        }
    }

    if (showMigrateDialog) {
        MigrateDialog(
            state = migrationState,
            onMigrate = { uid -> vm.migrateFromUid(uid) },
            onDismiss = {
                showMigrateDialog = false
                vm.resetMigration()
            }
        )
    }
}

@Composable
private fun MigrateDialog(
    state: MigrationState,
    onMigrate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var uid by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (state !is MigrationState.Loading) onDismiss() },
        title = { Text("Migruj dane z UID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Wklej stary Firebase UID z Firebase Console → Firestore → users/")
                OutlinedTextField(
                    value = uid,
                    onValueChange = { uid = it },
                    label = { Text("Stary UID") },
                    singleLine = true,
                    enabled = state !is MigrationState.Loading,
                    modifier = Modifier.fillMaxWidth()
                )
                when (state) {
                    is MigrationState.Loading -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Trwa migracja…")
                        }
                    }
                    is MigrationState.Success ->
                        Text("Zmigrowano ${state.count} pozycji. Gotowe!", color = MaterialTheme.colorScheme.primary)
                    is MigrationState.Error ->
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (state is MigrationState.Success) {
                TextButton(onClick = onDismiss) { Text("Zamknij") }
            } else {
                TextButton(
                    onClick = { onMigrate(uid) },
                    enabled = uid.isNotBlank() && state !is MigrationState.Loading
                ) { Text("Migruj") }
            }
        },
        dismissButton = {
            if (state !is MigrationState.Success) {
                TextButton(
                    onClick = onDismiss,
                    enabled = state !is MigrationState.Loading
                ) { Text("Anuluj") }
            }
        }
    )
}

@Preview
@Preview("dark theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeScreenPreview() {
    CCITheme {
        Surface {
            HomeScreen()
        }
    }
}

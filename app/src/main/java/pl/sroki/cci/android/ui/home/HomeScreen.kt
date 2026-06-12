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

    var query by remember { mutableStateOf("") }

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
                    Text(
                        text = uiState.userName ?: "",
                        modifier = Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
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
            NavigationItem(
                text = "Statystyki",
                icon = Icons.Filled.BarChart,
                enabled = false
            ) {}
            if (uiState.isLoggedIn) {
                NavigationItem(text = "Klasery", icon = Icons.Filled.FolderOpen) {
                    onClick(Screen.Binders)
                }
            }
        }
    }
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

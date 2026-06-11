package pl.sroki.cci.android.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import pl.sroki.cci.android.NavigationItem
import pl.sroki.cci.android.navigation.Screen
import pl.sroki.cci.android.ui.theme.CCITheme

@Composable
fun HomeScreen(
    onClick: (Screen) -> Unit = {},
    onSearch: (String) -> Unit = {}
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                if (searchVisible) {
                    SearchBar(
                        onSearch = onSearch,
                        onRequestClose = {
                            searchVisible = false
                        }
                    )
                } else {
                    Text(text = "Crowncaps.Info")
                }
            },
            actions = {
                if (!searchVisible) {
                    IconButton(onClick = { searchVisible = !searchVisible }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }
        )

        BackHandler(enabled = searchVisible) {
            searchVisible = false
        }
    }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            NavigationItem(text = "Picture search", icon = Icons.Filled.Star) {
                onClick(Screen.PictureSearch)
            }
            NavigationItem(text = "Additions", icon = Icons.Filled.Home) {
                onClick(Screen.Latest)
            }
            NavigationItem(text = "Countries", icon = Icons.Filled.Place) {
                onClick(Screen.Countries)
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
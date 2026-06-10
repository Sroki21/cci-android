package pl.sroki.cci.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import pl.sroki.cci.android.navigation.Screen
import pl.sroki.cci.android.ui.HomeScreen
import pl.sroki.cci.android.ui.catalog.caps.detail.CapDetailScreen
import pl.sroki.cci.android.ui.catalog.caps.quicksearch.QuickSearchScreen
import pl.sroki.cci.android.ui.catalog.countries.Countries
import pl.sroki.cci.android.ui.catalog.countries.CountriesViewModel
import pl.sroki.cci.android.ui.catalog.country.CountryCapsScreen
import pl.sroki.cci.android.ui.catalog.latest.LatestCapsScreen
import pl.sroki.cci.android.ui.catalog.picturesearch.PictureSearch
import pl.sroki.cci.android.ui.catalog.picturesearch.PictureSearchCapsScreen
import pl.sroki.cci.android.ui.theme.CCITheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CCITheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    Navigation()
                }
            }
        }
    }
}

@Composable
fun Navigation(
    countriesViewModel: CountriesViewModel = viewModel(),
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(route = Screen.Home.route) {
            HomeScreen(
                onClick = { screen ->
                    navController.navigate(screen.route)
                },
                onSearch = { query ->
                    navController.navigate(Screen.QuickSearchResults.createUrl(query))
                }
            )
        }
        composable(route = Screen.Countries.route) {
            countriesViewModel.getCountries()
            Countries(
                countriesUiState = countriesViewModel.countriesUiState,
                onBack = { navController.popBackStack() },
                onCountryClick = {
                    navController.navigate(
                        Screen.Country.createUrl(it.id, it.name)
                    )
                }
            )
        }
        composable(route = Screen.PictureSearch.route) {
            PictureSearch(
                onBack = { navController.popBackStack() },
                onSearch = { categories ->
                    navController.navigate(
                        Screen.PictureSearchResults.createUrl(categories)
                    )
                }
            )
        }
        composable(
            route = Screen.Country.route,
            arguments = listOf(
                navArgument("countryId") {
                    type = NavType.IntType
                },
                navArgument("name") {
                    type = NavType.StringType
                }
            )
        ) { backEntryState ->
            CountryCapsScreen(
                id = backEntryState.arguments?.getInt("countryId") ?: 0,
                name = backEntryState.arguments?.getString("name") ?: "",
                onBack = { navController.popBackStack() },
                onCapClick = {
                    navController.navigate(
                        Screen.CapDetail.createUrl(it.id)
                    )
                }
            )
        }
        composable(
            route = Screen.PictureSearchResults.route,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                }
            )
        ) { backEntryState ->
            PictureSearchCapsScreen(
                categoryIds = backEntryState.arguments?.getString("id")?.split(",")
                    ?.map { it.toInt() }
                    ?: listOf(),
                name = backEntryState.arguments?.getString("name") ?: "",
                onBack = { navController.popBackStack() },
                onCapClick = {
                    navController.navigate(
                        Screen.CapDetail.createUrl(it.id)
                    )
                }
            )
        }
        composable(
            route = Screen.QuickSearchResults.route,
            arguments = listOf(
                navArgument("query") {
                    type = NavType.StringType
                }
            )
        ) { backEntryState ->
            QuickSearchScreen(
                query = backEntryState.arguments?.getString("query") ?: "",
                onBack = { navController.popBackStack() },
                onCapClick = {
                    navController.navigate(
                        Screen.CapDetail.createUrl(it.id)
                    )
                }
            )
        }
        composable(
            route = Screen.Latest.route
        ) {
            LatestCapsScreen(
                onBack = { navController.popBackStack() },
                onCapClick = {
                    navController.navigate(
                        Screen.CapDetail.createUrl(it.id)
                    )
                }
            )
        }
        composable(
            route = Screen.CapDetail.route,
            arguments = listOf(
                navArgument("capId") {
                    type = NavType.IntType
                }
            )
        ) { backEntryState ->
            CapDetailScreen(
                id = backEntryState.arguments?.getInt("capId") ?: 0,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterialApi::class)
fun NavigationItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit = {}
) {
    ListItem(
        icon = { Icon(imageVector = icon, contentDescription = null) },
        text = { Text(text = text) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    CCITheme {
        Navigation()
    }
}

@Preview(showBackground = true)
@Composable
fun DarkThemePreview() {
    CCITheme(darkTheme = true) {
        Navigation()
    }
}
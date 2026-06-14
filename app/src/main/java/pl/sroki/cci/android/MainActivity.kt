package pl.sroki.cci.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import pl.sroki.cci.android.navigation.Screen
import pl.sroki.cci.android.ui.HomeScreen
import pl.sroki.cci.android.ui.catalog.caps.AssignedCapsViewModel
import pl.sroki.cci.android.ui.catalog.caps.LocalAssignedCapIds
import pl.sroki.cci.android.ui.catalog.caps.detail.CapDetailScreen
import pl.sroki.cci.android.ui.catalog.caps.quicksearch.QuickSearchScreen
import pl.sroki.cci.android.ui.catalog.countries.Countries
import pl.sroki.cci.android.ui.catalog.countries.CountriesViewModel
import pl.sroki.cci.android.ui.catalog.country.CountryCapsScreen
import pl.sroki.cci.android.ui.catalog.latest.LatestCapsScreen
import pl.sroki.cci.android.ui.auth.LoginScreen
import pl.sroki.cci.android.ui.binders.BindersScreen
import pl.sroki.cci.android.ui.catalog.caps.advanced.AdvancedSearchScreen
import pl.sroki.cci.android.ui.statistics.CountriesListScreen
import pl.sroki.cci.android.ui.statistics.map.LocationsMapScreen
import pl.sroki.cci.android.ui.statistics.verification.CollectionVerificationScreen
import pl.sroki.cci.android.ui.statistics.CountryOwnedCapsScreen
import pl.sroki.cci.android.ui.statistics.StatisticsScreen
import pl.sroki.cci.android.ui.catalog.picturesearch.PictureSearch
import pl.sroki.cci.android.ui.catalog.purchased.PurchasedScreen
import pl.sroki.cci.android.ui.theme.CCITheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CCITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
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
    assignedCapsViewModel: AssignedCapsViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val assignedCapIds by assignedCapsViewModel.assignedCapIds.collectAsState()

    CompositionLocalProvider(LocalAssignedCapIds provides assignedCapIds) {
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
                },
                onLoginClick = {
                    navController.navigate(Screen.Login.route)
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
                onCapClick = {
                    navController.navigate(Screen.CapDetail.createUrl(it.id))
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
        composable(route = Screen.AdvancedSearch.route) {
            AdvancedSearchScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it.id)) }
            )
        }
        composable(route = Screen.Login.route) {
            LoginScreen(onLoginSuccess = { navController.popBackStack() })
        }
        composable(route = Screen.Purchased.route) {
            PurchasedScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it.id)) }
            )
        }
        composable(route = Screen.Binders.route) {
            BindersScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it)) }
            )
        }
        composable(route = Screen.CollectionVerification.route) {
            CollectionVerificationScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it)) }
            )
        }
        composable(route = Screen.LocationsMap.route) {
            LocationsMapScreen(
                onBack = { navController.popBackStack() },
                onCountryClick = { navController.navigate(Screen.CountryOwnedCaps.createUrl(it)) }
            )
        }
        composable(route = Screen.Statistics.route) {
            StatisticsScreen(
                onBack = { navController.popBackStack() },
                onOpenCountries = { navController.navigate(Screen.OwnedCountries.route) },
                onOpenLocations = { navController.navigate(Screen.LocationsMap.route) },
                onCountryClick = { country ->
                    navController.navigate(Screen.CountryOwnedCaps.createUrl(country))
                }
            )
        }
        composable(route = Screen.OwnedCountries.route) {
            CountriesListScreen(
                onBack = { navController.popBackStack() },
                onCountryClick = { country ->
                    navController.navigate(Screen.CountryOwnedCaps.createUrl(country))
                }
            )
        }
        composable(
            route = Screen.CountryOwnedCaps.route,
            arguments = listOf(
                navArgument("country") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) {
            CountryOwnedCapsScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it.id)) }
            )
        }
    }
    } // CompositionLocalProvider
}

@Composable
fun NavigationItem(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    ListItem(
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        headlineContent = { Text(text = text) },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
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

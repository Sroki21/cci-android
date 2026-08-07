package pl.sroki.cci.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import pl.sroki.cci.android.data.SessionRepository
import pl.sroki.cci.android.data.datasource.local.CredentialsStore
import pl.sroki.cci.android.navigation.Screen
import javax.inject.Inject
import pl.sroki.cci.android.ui.HomeScreen
import pl.sroki.cci.android.ui.catalog.caps.AssignedCapsViewModel
import pl.sroki.cci.android.ui.catalog.caps.LocalAssignedCapIds
import pl.sroki.cci.android.ui.catalog.caps.detail.CapDetailScreen
import pl.sroki.cci.android.ui.catalog.caps.quicksearch.QuickSearchScreen
import pl.sroki.cci.android.ui.catalog.countries.Countries
import pl.sroki.cci.android.ui.catalog.countries.CountriesViewModel
import pl.sroki.cci.android.ui.catalog.country.CountryCapsScreen
import pl.sroki.cci.android.ui.catalog.latest.LatestCapsScreen
import pl.sroki.cci.android.ui.auth.ClearanceGate
import pl.sroki.cci.android.ui.auth.ClearanceViewModel
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
    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var credentialsStore: CredentialsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zapisane poświadczenia wystarczą — ReauthInterceptor odtworzy z nich sesję przy
        // pierwszym żądaniu, więc nie ma powodu pokazywać ekranu logowania.
        val isLoggedIn = sessionRepository.loadCachedToken() != null ||
            credentialsStore.hasCredentials()
        setContent {
            CCITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Navigation(isLoggedIn = isLoggedIn)
                }
            }
        }
    }
}

@Composable
fun Navigation(
    isLoggedIn: Boolean = false,
    assignedCapsViewModel: AssignedCapsViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val assignedCapIds by assignedCapsViewModel.assignedCapIds.collectAsStateWithLifecycle()

    // Gdy interceptor napotka bramkę Cloudflare, montujemy nakładkę z WebView rozwiązującym
    // challenge. Nakładka startuje poza ekranem: managed challenge przechodzi sam w ułamku
    // sekundy, a wcześniej użytkownik dostawał w tym miejscu pełnoekranowy ekran, który zaraz
    // znikał — mignięcie, w którym i tak nie było w co kliknąć.
    val clearanceViewModel = hiltViewModel<ClearanceViewModel>()
    val challengeRequired by clearanceViewModel.challengeRequired.collectAsStateWithLifecycle()
    var bramkaWidoczna by remember { mutableStateOf(false) }
    LaunchedEffect(challengeRequired) {
        bramkaWidoczna = false
        if (challengeRequired) {
            // Nie rozwiązał się w tle w tym czasie — to znaczy, że czeka na człowieka
            // (interaktywny Turnstile) albo się zaciął. Dopiero teraz pokazujemy bramkę.
            delay(PROG_POKAZANIA_BRAMKI_MS)
            bramkaWidoczna = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalAssignedCapIds provides assignedCapIds) {
    NavHost(
        navController = navController,
        startDestination = if (isLoggedIn) Screen.Home.route else Screen.Login.route
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
            // ViewModel powstaje razem z ekranem, nie z calym NavHostem, a pobranie idzie przez
            // LaunchedEffect — wywolanie wprost w ciele composable strzelalo do API przy KAZDEJ
            // rekompozycji, bo getCountries() nie jest idempotentne.
            val countriesViewModel = hiltViewModel<CountriesViewModel>()
            LaunchedEffect(Unit) { countriesViewModel.getCountries() }
            Countries(
                countriesUiState = countriesViewModel.countriesUiState,
                onBack = { navController.popBackStack() },
                onCountryClick = {
                    navController.navigate(
                        Screen.Country.createUrl(it.id, it.name)
                    )
                },
                onRetry = { countriesViewModel.getCountries() }
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
                onBack = { navController.popBackStack() },
                onProducerClick = { producerName ->
                    navController.navigate(Screen.AdvancedSearchByProducer.createUrl(producerName))
                },
                onCapNumberClick = { capId ->
                    navController.navigate(Screen.CapDetail.createUrl(capId))
                }
            )
        }
        composable(route = Screen.AdvancedSearch.route) {
            AdvancedSearchScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it.id)) }
            )
        }
        composable(
            route = Screen.AdvancedSearchByProducer.route,
            arguments = listOf(
                navArgument("producer") {
                    type = NavType.StringType
                }
            )
        ) {
            AdvancedSearchScreen(
                onBack = { navController.popBackStack() },
                onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it.id)) }
            )
        }
        composable(route = Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    // popBackStack() nie działa, gdy Login jest startDestination (świeża
                    // instalacja/po wylogowaniu) — jedyny wpis na stosie, nie ma dokąd wrócić,
                    // formularz zostawał na ekranie mimo udanego logowania. Nawigacja wprost
                    // na Home z usunięciem Login ze stosu działa niezależnie od tego, czy Login
                    // był korzeniem grafu, czy został otwarty z Home.
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
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

        // Na wierzchu, żeby po pokazaniu przykryła aplikację; dopóki niewidoczna, sama odsuwa
        // się poza ekran i nie łapie dotknięć.
        if (challengeRequired) {
            ClearanceGate(
                visible = bramkaWidoczna,
                onClose = { bramkaWidoczna = false },
            )
        }
    } // Box
}

// Ile czekamy, aż challenge rozwiąże się sam w tle, zanim pokażemy bramkę użytkownikowi.
// Managed challenge mieści się zwykle w 1–3 s; dłuższe czekanie znaczy, że ktoś musi kliknąć.
private const val PROG_POKAZANIA_BRAMKI_MS = 6_000L

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

// Podglady DefaultPreview/DarkThemePreview usuniete: wolaly Navigation(), ktorego domyslny
// assignedCapsViewModel to hiltViewModel() — w podgladzie nie ma komponentu Hilta, wiec oba
// wysypywaly sie przy renderowaniu i nigdy niczego nie pokazywaly.

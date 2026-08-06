package pl.sroki.cci.android.ui.catalog.countries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import pl.sroki.cci.android.data.fakeCountries
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.ui.components.ErrorWithRetry
import pl.sroki.cci.android.ui.components.FullSizeLoader
import pl.sroki.cci.android.ui.theme.CCITheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Countries(
    countriesUiState: CountriesUiState,
    onCountryClick: (Country) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(text = "Kraje")
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
    }) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (countriesUiState) {
                is CountriesUiState.Loading -> FullSizeLoader()
                is CountriesUiState.Success -> LazyColumn(
                    modifier = modifier.fillMaxSize()
                ) {
                    items(countriesUiState.countries, key = { it.id }) { country ->
                        CountryItem(
                            country = country,
                            onClick = { onCountryClick(country) }
                        )
                    }
                }
                // Wcześniej dosłownie Text("Error"): bez treści błędu i bez sposobu na ponowienie.
                is CountriesUiState.Error -> ErrorWithRetry(
                    message = "Nie udało się pobrać listy krajów",
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
fun CountryItem(country: Country, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        leadingContent = {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(country.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = country.name,
                modifier = Modifier
                    .width(24.dp)
                    .height(18.dp),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = { Text(text = country.name) },
        modifier = modifier.clickable(onClick = onClick)
    )
}

@Preview(widthDp = 320, heightDp = 400, backgroundColor = 0xFFFFFFFF)
@Composable
private fun CountryItemPreview() {
    CCITheme {
        Countries(
            countriesUiState = CountriesUiState.Success(countries = fakeCountries),
            onCountryClick = {},
            onBack = {}
        )
    }
}

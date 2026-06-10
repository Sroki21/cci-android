package pl.sroki.cci.android.ui.catalog.countries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import pl.sroki.cci.android.ui.components.FullSizeLoader
import pl.sroki.cci.android.ui.theme.CCITheme

@Composable
fun Countries(
    countriesUiState: CountriesUiState,
    onCountryClick: (Country) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(text = "Countries")
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
    }) { innerPadding ->
        when (countriesUiState) {
            is CountriesUiState.Loading -> FullSizeLoader()
            is CountriesUiState.Success -> LazyColumn(
                modifier = modifier.fillMaxSize()
            ) {
                itemsIndexed(countriesUiState.countries) { _, country ->
                    CountryItem(
                        country = country,
                        onClick = { onCountryClick(country) }
                    )
                }
            }
            is CountriesUiState.Error -> Text("Error")
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun CountryItem(country: Country, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        icon = {
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
        text = { Text(text = country.name) },
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
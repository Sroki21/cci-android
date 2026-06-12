package pl.sroki.cci.android.ui.catalog.caps.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.AdvancedSearchFilter
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.SearchOperator
import pl.sroki.cci.android.ui.catalog.caps.CapsView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSearchScreen(
    onBack: () -> Unit,
    onCapClick: (Cap) -> Unit
) {
    val viewModel = hiltViewModel<AdvancedSearchViewModel>()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val totalResults by viewModel.totalResults.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val caps = viewModel.caps.collectAsLazyPagingItems()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Szukanie zaawansowane") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                }
            }
        )
    }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FilterForm(
                filter = viewModel.filter,
                countries = viewModel.countries,
                isLoggedIn = isLoggedIn,
                onFilterChange = viewModel::updateFilter,
                onSearch = viewModel::search
            )
            HorizontalDivider()
            if (hasSearched) {
                totalResults?.let {
                    Text(
                        text = "Znaleziono: $it kapsli",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    CapsView(caps = caps, onCapClick = onCapClick)
                }
            }
        }
    }
}

@Composable
private fun FilterForm(
    filter: AdvancedSearchFilter,
    countries: List<Country>,
    isLoggedIn: Boolean,
    onFilterChange: (AdvancedSearchFilter) -> Unit,
    onSearch: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SimpleFilterRow(
            label = "ID",
            value = filter.idValue,
            onValueChange = { onFilterChange(filter.copy(idValue = it)) },
            keyboardType = KeyboardType.Number
        )
        OperatorFilterRow(
            label = "Tekst",
            value = filter.textValue,
            operator = filter.textOperator,
            onValueChange = { onFilterChange(filter.copy(textValue = it)) },
            onOperatorChange = { onFilterChange(filter.copy(textOperator = it)) }
        )
        CountryFilterRow(
            countryName = filter.countryName,
            countries = countries,
            onCountrySelected = { country ->
                if (country == null) {
                    onFilterChange(filter.copy(countryId = null, countryName = ""))
                } else {
                    onFilterChange(filter.copy(countryId = country.id.toInt(), countryName = country.name))
                }
            }
        )
        OperatorFilterRow(
            label = "Producent",
            value = filter.producerValue,
            operator = filter.producerOperator,
            onValueChange = { onFilterChange(filter.copy(producerValue = it)) },
            onOperatorChange = { onFilterChange(filter.copy(producerOperator = it)) }
        )
        if (isLoggedIn) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFilterChange(filter.copy(onlyInCollection = !filter.onlyInCollection)) }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(
                    checked = filter.onlyInCollection,
                    onCheckedChange = { onFilterChange(filter.copy(onlyInCollection = it)) }
                )
                Text("Tylko kapsle w kolekcji")
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onSearch,
            enabled = !filter.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Szukaj")
        }
    }
}

@Composable
private fun SimpleFilterRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun OperatorFilterRow(
    label: String,
    value: String,
    operator: SearchOperator,
    onValueChange: (String) -> Unit,
    onOperatorChange: (SearchOperator) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(130.dp)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(operator.label, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SearchOperator.entries.forEach { op ->
                    DropdownMenuItem(
                        text = { Text(op.label) },
                        onClick = { onOperatorChange(op); expanded = false }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CountryFilterRow(
    countryName: String,
    countries: List<Country>,
    onCountrySelected: (Country?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogSearch by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = countryName,
                onValueChange = {},
                label = { Text("Kraj") },
                readOnly = true,
                trailingIcon = {
                    if (countryName.isNotEmpty()) {
                        IconButton(onClick = { onCountrySelected(null) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Wyczyść")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Box(modifier = Modifier.matchParentSize().clickable { showDialog = true })
        }
    }

    if (showDialog) {
        val filtered = remember(dialogSearch, countries) {
            if (dialogSearch.isBlank()) countries
            else countries.filter { it.name.contains(dialogSearch, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialogSearch = "" },
            title = { Text("Wybierz kraj") },
            text = {
                Column {
                    OutlinedTextField(
                        value = dialogSearch,
                        onValueChange = { dialogSearch = it },
                        label = { Text("Szukaj") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(filtered, key = { it.id }) { country ->
                            ListItem(
                                headlineContent = { Text(country.name) },
                                modifier = Modifier.clickable {
                                    onCountrySelected(country)
                                    showDialog = false
                                    dialogSearch = ""
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

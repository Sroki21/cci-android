package pl.sroki.cci.android.ui.catalog.caps.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import pl.sroki.cci.android.model.Producer
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
                producers = viewModel.producers,
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
    producers: List<Producer>,
    isLoggedIn: Boolean,
    onFilterChange: (AdvancedSearchFilter) -> Unit,
    onSearch: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OperatorFilterRow(
            label = "Tekst",
            value = filter.textValue,
            operator = filter.textOperator,
            onValueChange = { onFilterChange(filter.copy(textValue = it)) },
            onOperatorChange = { onFilterChange(filter.copy(textOperator = it)) }
        )
        ProducerFilterRow(
            producerName = filter.producerName,
            producers = producers,
            onProducerSelected = { producer ->
                if (producer == null) {
                    onFilterChange(filter.copy(producerId = null, producerName = ""))
                } else {
                    onFilterChange(filter.copy(producerId = producer.id, producerName = producer.name))
                }
            }
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
        SimpleFilterRow(
            label = "ID",
            value = filter.idValue,
            onValueChange = { onFilterChange(filter.copy(idValue = it)) },
            keyboardType = KeyboardType.Number
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
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Wyczyść")
                }
            }
        },
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
        Box(modifier = Modifier.width(95.dp)) {
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
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Wyczyść")
                    }
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProducerFilterRow(
    producerName: String,
    producers: List<Producer>,
    onProducerSelected: (Producer?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogSearch by remember { mutableStateOf("") }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) showDialog = true
        }
    }

    OutlinedTextField(
        value = producerName,
        onValueChange = {},
        label = { Text("Producent") },
        readOnly = true,
        interactionSource = interactionSource,
        trailingIcon = {
            if (producerName.isNotEmpty()) {
                IconButton(onClick = { onProducerSelected(null) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Wyczyść producenta")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )

    if (showDialog) {
        val filtered = remember(dialogSearch, producers) {
            if (dialogSearch.isBlank()) producers
            else producers.filter { it.name.contains(dialogSearch, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialogSearch = "" },
            title = { Text("Wybierz producenta") },
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
                        items(filtered, key = { it.id }) { producer ->
                            ListItem(
                                headlineContent = { Text(producer.name) },
                                supportingContent = producer.city?.takeIf { it.isNotBlank() }
                                    ?.let { { Text(it) } },
                                modifier = Modifier.clickable {
                                    onProducerSelected(producer)
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

@Composable
private fun CountryFilterRow(
    countryName: String,
    countries: List<Country>,
    onCountrySelected: (Country?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogSearch by remember { mutableStateOf("") }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) showDialog = true
        }
    }

    OutlinedTextField(
        value = countryName,
        onValueChange = {},
        label = { Text("Kraj") },
        readOnly = true,
        interactionSource = interactionSource,
        trailingIcon = {
            if (countryName.isNotEmpty()) {
                IconButton(onClick = { onCountrySelected(null) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Wyczyść kraj")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )

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

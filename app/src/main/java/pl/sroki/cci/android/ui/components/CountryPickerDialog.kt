package pl.sroki.cci.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.sroki.cci.android.data.model.Country

/**
 * Dialog wyboru kraju z wyszukiwarką.
 *
 * Wspólny dla Klaserów i Szukania zaawansowanego — wcześniej ta sama implementacja istniała
 * w obu ekranach w dwóch niezależnych kopiach. Samo pole tekstowe zostaje po stronie ekranów,
 * bo różnią się jego zagęszczeniem.
 */
@Composable
fun CountryPickerDialog(
    countries: List<Country>,
    onCountrySelected: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, countries) {
        if (search.isBlank()) countries
        else countries.filter { it.name.contains(search, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wybierz kraj") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Szukaj") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(filtered, key = { it.id }) { country ->
                        ListItem(
                            headlineContent = { Text(country.name) },
                            modifier = Modifier.clickable { onCountrySelected(country) }
                        )
                    }
                }
            }
        },
        // Wybór następuje kliknięciem pozycji, więc nie ma czego potwierdzać — ale dialog
        // musi dać się zamknąć inaczej niż kliknięciem obok (wcześniej confirmButton był pusty).
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}

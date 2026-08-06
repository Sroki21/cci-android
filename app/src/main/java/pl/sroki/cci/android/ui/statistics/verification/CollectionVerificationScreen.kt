package pl.sroki.cci.android.ui.statistics.verification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import pl.sroki.cci.android.model.binder.CachedCap
import pl.sroki.cci.android.model.binder.CatalogStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionVerificationScreen(
    onBack: () -> Unit,
    onCapClick: (Long) -> Unit,
) {
    val viewModel = hiltViewModel<CollectionVerificationViewModel>()
    val flagged by viewModel.flaggedCaps.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Weryfikacja kolekcji") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                }
            }
        )
    }) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            if (scan.running) {
                // Dopóki nie znamy liczby kapsli, pasek ma być nieokreślony. Wyliczony postęp
                // 0/0 stał nieruchomo na zerze i faza zbierania listy wyglądała jak zawieszenie.
                if (scan.total > 0) {
                    LinearProgressIndicator(
                        progress = { scan.done.toFloat() / scan.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Weryfikacja ${scan.done}/${scan.total}", modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancelScan) { Text("Anuluj") }
                }
            } else {
                Button(
                    onClick = viewModel::runFullScan,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Zweryfikuj całość") }
            }

            if (scan.failed) {
                // Skan nie dotknął katalogu ani razu — werdykt o kolekcji byłby zmyślony.
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Nie udało się połączyć z katalogiem",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Kolekcja nie została sprawdzona. Spróbuj ponownie, gdy wróci połączenie.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else if (flagged.isEmpty()) {
                // Werdykt dopiero po skanie — wcześniej "Brak rozjazdów" wisiało pod paskiem
                // postępu od pierwszej sekundy, zanim cokolwiek zostało sprawdzone.
                if (!scan.running) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Brak rozjazdów")
                    }
                }
            } else {
                LazyColumn {
                    items(flagged, key = { it.capId }) { cap ->
                        FlaggedRow(
                            cap = cap,
                            onClick = { onCapClick(cap.capId) },
                            onKeep = { viewModel.keep(cap.capId) },
                            onUnlink = { viewModel.unlink(cap.capId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FlaggedRow(
    cap: CachedCap,
    onClick: () -> Unit,
    onKeep: () -> Unit,
    onUnlink: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = cap.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = cap.name.ifBlank { "Kapsel ${cap.capId}" },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = statusLabel(cap.catalogStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onKeep) { Text("Zachowaj") }
            TextButton(onClick = onUnlink) { Text("Odepnij") }
        }
    }
}

// Wyczerpujacy when bez galezi else: dodanie statusu do CatalogStatus przestanie sie
// kompilowac, dopoki nie dostanie tu etykiety.
private fun statusLabel(status: CatalogStatus): String = when (status) {
    CatalogStatus.SWAPPED -> "Podmieniony — inny kapsel pod tym ID"
    CatalogStatus.UPDATED -> "Zmieniony w katalogu"
    CatalogStatus.MISSING -> "Usunięty z katalogu"
    CatalogStatus.PRODUCER_REMOVED -> "Wybrany producent/kraj już nie istnieje"
    CatalogStatus.OK -> "Zgodny z katalogiem"
    CatalogStatus.UNKNOWN -> "Niesprawdzony"
}

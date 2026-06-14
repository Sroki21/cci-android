package pl.sroki.cci.android.ui.statistics.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import pl.sroki.cci.android.ui.components.FullSizeLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsMapScreen(
    onBack: () -> Unit,
    onCountryClick: (String) -> Unit = {},
) {
    val viewModel = hiltViewModel<LocationsMapViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    var showLicense by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Lokalizacje") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Wstecz"
                    )
                }
            },
            actions = {
                IconButton(onClick = { showLicense = true }) {
                    Icon(Icons.Filled.Info, contentDescription = "O mapie")
                }
            }
        )
    }) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (val state = uiState) {
                is LocationsMapUiState.Loading -> FullSizeLoader()
                is LocationsMapUiState.Error -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = viewModel::load, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Spróbuj ponownie")
                    }
                }
                is LocationsMapUiState.Success -> SuccessContent(state, onCountryClick)
            }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("O mapie") },
            text = {
                Text(
                    "Mapa świata: Al MacDonald / Fritz Lekschas (simple-world-map), " +
                        "licencja CC BY-SA 3.0."
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("OK") } }
        )
    }
}

@Composable
private fun SuccessContent(
    state: LocationsMapUiState.Success,
    onCountryClick: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<MapCountry?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "${state.ownedCountriesCount} ${countriesLabel(state.ownedCountriesCount)} · " +
                "${state.totalCaps} ${capsLabel(state.totalCaps)}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            WorldMapCanvas(
                state = state,
                selectedIso = selected?.iso,
                onCountrySelected = { iso -> selected = state.countries[iso] },
                modifier = Modifier.fillMaxSize()
            )

            selected?.let { country ->
                SelectedCountryCard(
                    country = country,
                    onClick = if (country.count > 0) {
                        { onCountryClick(country.apiName) }
                    } else null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }
        }
    }
}

private const val MAX_ZOOM = 8f

@Composable
private fun WorldMapCanvas(
    state: LocationsMapUiState.Success,
    selectedIso: String?,
    onCountrySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val oceanColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val landColor = MaterialTheme.colorScheme.surfaceVariant
    val ownedColor = MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val selectedBorder = MaterialTheme.colorScheme.tertiary

    val viewBox = state.map.viewBox

    // Transformacja użytkownika (zoom/pan) nakładana na transformację dopasowania do ekranu.
    var userScale by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    // Parametry dopasowania viewBox -> ekran; ustalane przy pierwszym rysowaniu, używane też w hit-teście.
    var fitScale by remember { mutableFloatStateOf(0f) }
    var fitOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (userScale * zoom).coerceIn(1f, MAX_ZOOM)
                        val k = newScale / userScale
                        var next = (userOffset - centroid) * k + centroid + pan
                        // Nie pozwól odsunąć mapy całkiem poza widok.
                        val maxX = ((size.width * newScale - size.width) / 2f).coerceAtLeast(0f)
                        val maxY = ((size.height * newScale - size.height) / 2f).coerceAtLeast(0f)
                        next = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                        userScale = newScale
                        userOffset = next
                    }
                }
                .pointerInput(state.map) {
                    detectTapGestures { tap ->
                        if (fitScale <= 0f) return@detectTapGestures
                        // Ekran -> przestrzeń viewBox (odwrócenie transformacji rysowania).
                        val base = (tap - userOffset) / userScale
                        val vx = (base.x - fitOffset.x) / fitScale + viewBox.minX
                        val vy = (base.y - fitOffset.y) / fitScale + viewBox.minY
                        val iso = state.map.countryAt(vx, vy) ?: return@detectTapGestures
                        if (state.countries.containsKey(iso)) onCountrySelected(iso)
                    }
                }
        ) {
            drawRect(color = oceanColor)
            if (viewBox.width <= 0f || viewBox.height <= 0f) return@Canvas

            fitScale = minOf(size.width / viewBox.width, size.height / viewBox.height)
            fitOffset = Offset(
                (size.width - viewBox.width * fitScale) / 2f,
                (size.height - viewBox.height * fitScale) / 2f,
            )

            withTransform({
                translate(userOffset.x, userOffset.y)
                scale(userScale, userScale, pivot = Offset.Zero)
                translate(fitOffset.x, fitOffset.y)
                scale(fitScale, fitScale, pivot = Offset.Zero)
                translate(-viewBox.minX, -viewBox.minY)
            }) {
                val strokeWidth = 0.3f / (fitScale * userScale)
                state.map.countries.forEach { (iso, path) ->
                    val composePath = path.asComposePath()
                    val owned = (state.countries[iso]?.count ?: 0) > 0
                    drawPath(composePath, color = if (owned) ownedColor else landColor, style = Fill)
                    val isSelected = iso == selectedIso
                    drawPath(
                        composePath,
                        color = if (isSelected) selectedBorder else borderColor,
                        style = Stroke(width = if (isSelected) strokeWidth * 4f else strokeWidth)
                    )
                }
            }
        }

        if (userScale > 1f || userOffset != Offset.Zero) {
            FilledTonalIconButton(
                onClick = { userScale = 1f; userOffset = Offset.Zero },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Wyśrodkuj mapę")
            }
        }
    }
}

@Composable
private fun SelectedCountryCard(
    country: MapCountry,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cardModifier = if (onClick != null) {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Card(modifier = cardModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (country.flagUrl != null) {
                AsyncImage(
                    model = country.flagUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 42.dp, height = 28.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(country.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${country.count} ${capsLabel(country.count)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    }
}

/** Polska odmiana: 1 kapsel, 2–4 kapsle, reszta kapsli. */
private fun capsLabel(n: Int): String = when {
    n == 1 -> "kapsel"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "kapsle"
    else -> "kapsli"
}

/** Polska odmiana: 1 kraj, 2–4 kraje, reszta krajów. */
private fun countriesLabel(n: Int): String = when {
    n == 1 -> "kraj"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "kraje"
    else -> "krajów"
}

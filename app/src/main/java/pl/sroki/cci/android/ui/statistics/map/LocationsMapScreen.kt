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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
}

@Composable
private fun SuccessContent(
    state: LocationsMapUiState.Success,
    onCountryClick: (String) -> Unit,
) {
    // Trzymamy ISO, nie caly obiekt: po odswiezeniu danych karta ma pokazywac aktualna liczbe
    // kapsli, a nie te zapamietana w chwili klikniecia.
    var selectedIso by remember { mutableStateOf<String?>(null) }
    val selected = selectedIso?.let { state.countries[it] }

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
                selectedIso = selectedIso,
                onCountrySelected = { iso -> selectedIso = iso },
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

private const val MAX_ZOOM = 16f

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

    // Rozmiar obszaru rysowania; dopasowanie viewBox -> ekran liczone z niego raz, nie w trakcie
    // rysowania. Wcześniej fitScale/fitOffset były zapisywane wewnątrz draw scope i czytane
    // przez handlery gestów — działało, ale zapis stanu w fazie rysowania jest kruchy.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(canvasSize, viewBox) {
        if (canvasSize.width == 0 || viewBox.width <= 0f || viewBox.height <= 0f) {
            null
        } else {
            val scale = minOf(canvasSize.width / viewBox.width, canvasSize.height / viewBox.height)
            MapFit(
                scale = scale,
                offset = Offset(
                    (canvasSize.width - viewBox.width * scale) / 2f,
                    (canvasSize.height - viewBox.height * scale) / 2f,
                ),
                contentWidth = viewBox.width * scale,
                contentHeight = viewBox.height * scale,
            )
        }
    }

    // Konwersja android.graphics.Path -> Path Compose raz na mapę, a nie dla ~200 krajów
    // przy każdej klatce przeciągania.
    val composePaths = remember(state.map) {
        state.map.countries.map { (iso, path) -> iso to path.asComposePath() }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(fit) {
                    val currentFit = fit ?: return@pointerInput
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (userScale * zoom).coerceIn(1f, MAX_ZOOM)
                        val k = newScale / userScale
                        val next = (userOffset - centroid) * k + centroid + pan
                        userScale = newScale
                        userOffset = clampMapOffset(
                            offset = next,
                            scale = newScale,
                            viewportWidth = size.width.toFloat(),
                            viewportHeight = size.height.toFloat(),
                            fit = currentFit,
                        )
                    }
                }
                .pointerInput(state.map, fit) {
                    val currentFit = fit ?: return@pointerInput
                    detectTapGestures { tap ->
                        // Ekran -> przestrzeń viewBox (odwrócenie transformacji rysowania).
                        val base = (tap - userOffset) / userScale
                        val vx = (base.x - currentFit.offset.x) / currentFit.scale + viewBox.minX
                        val vy = (base.y - currentFit.offset.y) / currentFit.scale + viewBox.minY
                        val iso = state.map.countryAt(vx, vy) ?: return@detectTapGestures
                        if (state.countries.containsKey(iso)) onCountrySelected(iso)
                    }
                }
        ) {
            drawRect(color = oceanColor)
            if (fit == null) return@Canvas

            withTransform({
                translate(userOffset.x, userOffset.y)
                scale(userScale, userScale, pivot = Offset.Zero)
                translate(fit.offset.x, fit.offset.y)
                scale(fit.scale, fit.scale, pivot = Offset.Zero)
                translate(-viewBox.minX, -viewBox.minY)
            }) {
                val strokeWidth = 0.3f / (fit.scale * userScale)
                composePaths.forEach { (iso, composePath) ->
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

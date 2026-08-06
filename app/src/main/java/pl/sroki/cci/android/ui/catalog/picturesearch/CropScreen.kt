package pl.sroki.cci.android.ui.catalog.picturesearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Dłuższy bok wczytanego zdjęcia — z zapasem ponad [OUTPUT_SIZE] i ponad rozdzielczość ekranu. */
private const val MAX_DECODED_DIMENSION = 2048

/** Bok wysyłanego wycinka. Zdjęcia mniejsze niż to nie są rozciągane. */
private const val OUTPUT_SIZE = 800

private const val JPEG_QUALITY = 90

private sealed interface CropImageState {
    data object Loading : CropImageState

    data class Ready(val bitmap: Bitmap) : CropImageState

    data object Failed : CropImageState
}

@Composable
fun CropScreen(
    sourceUri: Uri,
    onConfirm: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(sourceUri) { mutableStateOf<CropImageState>(CropImageState.Loading) }
    var saving by remember { mutableStateOf(false) }

    // Kadrowanie nie jest osobną trasą, tylko zasłania ekran szukania — bez tego systemowy
    // Wstecz cofał nawigację o cały ekran zamiast wrócić do wyboru zdjęcia.
    BackHandler(enabled = !saving, onBack = onDismiss)

    LaunchedEffect(sourceUri) {
        state = withContext(Dispatchers.IO) {
            decodeForCrop(context, sourceUri)
                ?.let { CropImageState.Ready(it) }
                ?: CropImageState.Failed
        }
    }

    when (val current = state) {
        CropImageState.Loading -> CropMessage(message = null, onDismiss = onDismiss)

        CropImageState.Failed -> CropMessage(
            message = "Nie udało się otworzyć tego zdjęcia. Wybierz inne.",
            onDismiss = onDismiss,
        )

        is CropImageState.Ready -> CropContent(
            bitmap = current.bitmap,
            saving = saving,
            onConfirm = { window ->
                saving = true
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        writeCrop(context, current.bitmap, window)
                    }
                    saving = false
                    if (uri != null) onConfirm(uri) else state = CropImageState.Failed
                }
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Wczytuje zdjęcie w rozdzielczości wystarczającej do kadrowania.
 *
 * `runCatching` łapie także `OutOfMemoryError`, bo to najczęstszy sposób, w jaki dekodowanie
 * zdjęcia potrafi się wywrócić; poza tym wygasłe URI z galerii rzuca `SecurityException`,
 * a uszkodzony plik — `IOException`. Wcześniej każdy z tych przypadków kończył się wywaleniem
 * aplikacji, bo leciał nieprzechwycony przez `LaunchedEffect`.
 */
private fun decodeForCrop(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DECODED_DIMENSION)
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null

    applyExifOrientation(context, uri, decoded)
}.getOrNull()

// Zdjęcia z aparatu mają piksele zapisane poziomo z tagiem EXIF Orientation opisującym
// docelowy obrót — BitmapFactory go ignoruje, więc trzeba obrócić ręcznie. Galeria zwraca
// zwykle już wyprostowane bitmapy, ale funkcja jest bezpieczna też dla ORIENTATION_NORMAL.
private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bitmap
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/**
 * Wycina kółko ze zdjęcia i zapisuje je jako JPEG w cache. Woła się z wątku roboczego — samo
 * wycinanie, skalowanie i kompresja potrafiły zacinać ekran na kilkaset milisekund.
 */
private fun writeCrop(context: Context, source: Bitmap, window: CropWindow): Uri? = runCatching {
    val cropped = Bitmap.createBitmap(source, window.left, window.top, window.size, window.size)
    // Skalujemy wyłącznie w dół: rozdmuchiwanie wycinka mniejszego niż OUTPUT_SIZE tylko rozmywa
    // obraz i powiększa wysyłany plik, nie dokładając ani jednego szczegółu.
    val target = minOf(OUTPUT_SIZE, window.size)
    val out = if (cropped.width == target) {
        cropped
    } else {
        Bitmap.createScaledBitmap(cropped, target, target, true)
    }

    val file = newCropFile(context.cacheDir)
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }

    // createBitmap i createScaledBitmap potrafią oddać oryginał zamiast kopii — wtedy zwolnienie
    // wyniku zabiłoby bitmapę, którą ekran nadal rysuje.
    if (cropped !== source && cropped !== out) cropped.recycle()
    if (out !== source) out.recycle()

    Uri.fromFile(file)
}.getOrNull()

/** Ekran w czasie wczytywania i po nieudanym wczytaniu — w obu wypadkach z wyjściem. */
@Composable
private fun CropMessage(message: String?, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (message == null) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            )
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text("Anuluj")
        }
    }
}

@Composable
private fun CropContent(
    bitmap: Bitmap,
    saving: Boolean,
    onConfirm: (CropWindow) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val viewportCenter = Offset(viewportWidth / 2f, viewportHeight / 2f)
        val circleRadius = minOf(viewportWidth, viewportHeight) * 0.42f

        val minScale = minCropScale(bitmap.width, bitmap.height, circleRadius)
        val initialScale = maxOf(
            viewportWidth / bitmap.width,
            viewportHeight / bitmap.height,
            minScale,
        )

        // Klucz na skali początkowej: gdy zmieni się rozmiar obszaru, zapamiętany zoom mógłby
        // być mniejszy od nowego minimum i zostawić pustkę pod kółkiem.
        var scale by remember(initialScale) { mutableFloatStateOf(initialScale) }
        var offset by remember(initialScale) { mutableStateOf(Offset.Zero) }
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, circleRadius, initialScale) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(minScale, initialScale * 8f)
                        val zoomed = zoomedOffset(offset, scale, newScale, centroid, viewportCenter)
                        scale = newScale
                        offset = clampCropOffset(
                            zoomed + pan,
                            newScale,
                            bitmap.width,
                            bitmap.height,
                            circleRadius,
                        )
                    }
                },
        ) {
            drawRect(Color.Black)

            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(
                    (size.width / 2f + offset.x - bitmap.width * scale / 2f).roundToInt(),
                    (size.height / 2f + offset.y - bitmap.height * scale / 2f).roundToInt(),
                ),
                dstSize = IntSize(
                    (bitmap.width * scale).roundToInt(),
                    (bitmap.height * scale).roundToInt(),
                ),
            )

            val overlayPath = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addOval(
                    Rect(
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = circleRadius,
                    ),
                )
            }
            drawPath(overlayPath, Color.Black.copy(alpha = 0.65f))
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text("Anuluj")
            }
            Button(
                onClick = {
                    onConfirm(
                        cropWindow(offset, scale, bitmap.width, bitmap.height, circleRadius),
                    )
                },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text("Zatwierdź")
            }
        }
    }
}

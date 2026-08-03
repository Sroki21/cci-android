package pl.sroki.cci.android.ui.catalog.picturesearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun CropScreen(
    sourceUri: Uri,
    onConfirm: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(sourceUri) {
        withContext(Dispatchers.IO) {
            val decoded = context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it)
            }
            bitmap = decoded?.let { applyExifOrientation(context, sourceUri, it) }
        }
    }

    bitmap?.let { bmp ->
        CropContent(
            bitmap = bmp,
            onConfirm = { cropped ->
                val file = File(context.cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                cropped.recycle()
                onConfirm(Uri.fromFile(file))
            },
            onDismiss = onDismiss
        )
    }
}

// Zdjęcia z aparatu mają piksele zapisane poziomo z tagiem EXIF Orientation opisującym
// docelowy obrót — BitmapFactory go ignoruje, więc trzeba obrócić ręcznie. Galeria zwraca
// zwykle już wyprostowane bitmapy, ale funkcja jest bezpieczna też dla ORIENTATION_NORMAL.
private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
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

@Composable
private fun CropContent(
    bitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val circleRadius = minOf(screenW, screenH) * 0.42f

        val minScale = maxOf(
            circleRadius * 2f / bitmap.width,
            circleRadius * 2f / bitmap.height
        )
        val initialScale = maxOf(screenW / bitmap.width, screenH / bitmap.height, minScale)

        var scale by remember { mutableFloatStateOf(initialScale) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        fun coerceOffset(raw: Offset, s: Float): Offset {
            val mx = (bitmap.width * s / 2f - circleRadius).coerceAtLeast(0f)
            val my = (bitmap.height * s / 2f - circleRadius).coerceAtLeast(0f)
            return Offset(raw.x.coerceIn(-mx, mx), raw.y.coerceIn(-my, my))
        }

        val transformState = rememberTransformableState { zoom, pan, _ ->
            val newScale = (scale * zoom).coerceIn(minScale, initialScale * 8f)
            scale = newScale
            offset = coerceOffset(offset + pan, newScale)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(transformState)
        ) {
            drawRect(Color.Black)

            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(
                    (size.width / 2f + offset.x - bitmap.width * scale / 2f).roundToInt(),
                    (size.height / 2f + offset.y - bitmap.height * scale / 2f).roundToInt()
                ),
                dstSize = IntSize(
                    (bitmap.width * scale).roundToInt(),
                    (bitmap.height * scale).roundToInt()
                )
            )

            val overlayPath = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addOval(Rect(center = Offset(size.width / 2f, size.height / 2f), radius = circleRadius))
            }
            drawPath(overlayPath, Color.Black.copy(alpha = 0.65f))
            drawCircle(
                color = Color.White,
                radius = circleRadius,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Anuluj")
            }
            Button(
                onClick = {
                    val halfSize = circleRadius / scale
                    val cx = bitmap.width / 2f - offset.x / scale
                    val cy = bitmap.height / 2f - offset.y / scale
                    val left = (cx - halfSize).roundToInt().coerceIn(0, bitmap.width - 1)
                    val top = (cy - halfSize).roundToInt().coerceIn(0, bitmap.height - 1)
                    val cropSize = (halfSize * 2).roundToInt()
                        .coerceAtMost(bitmap.width - left)
                        .coerceAtMost(bitmap.height - top)
                        .coerceAtLeast(1)
                    val cropped = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize)
                    val out = Bitmap.createScaledBitmap(cropped, 800, 800, true)
                    if (cropped !== out) cropped.recycle()
                    onConfirm(out)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Zatwierdź")
            }
        }
    }
}

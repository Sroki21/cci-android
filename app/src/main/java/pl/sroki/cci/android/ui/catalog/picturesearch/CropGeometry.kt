package pl.sroki.cci.android.ui.catalog.picturesearch

import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

/**
 * Geometria ekranu kadrowania.
 *
 * Zdjęcie rysowane jest wokół środka obszaru, przesunięte o `offset` i przeskalowane o `scale`,
 * więc piksel `p` bitmapy trafia na ekran w punkcie
 * `screen = viewportCenter + offset + scale · (p − bitmapCenter)`.
 * Wszystkie funkcje poniżej wynikają wprost z tego jednego wzoru.
 */

/** Wycinek bitmapy odpowiadający kółku kadrowania, w pikselach bitmapy. */
internal data class CropWindow(val left: Int, val top: Int, val size: Int)

/**
 * Krotność podpróbkowania przy dekodowaniu, tak żeby dłuższy bok zmieścił się w [maxDimension].
 *
 * `BitmapFactory` akceptuje tylko potęgi dwójki i zaokrągla w dół, więc liczymy je wprost.
 * Bez tego zdjęcie z aparatu wchodziło do pamięci w pełnej rozdzielczości — 50 Mpx to ok. 200 MB
 * w ARGB_8888, a obrót według EXIF na chwilę podwaja tę liczbę.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > maxDimension) {
        sample *= 2
    }
    return sample
}

/**
 * Najmniejsza skala, przy której zdjęcie nadal przykrywa całe kółko — w obu osiach szerokość
 * zdjęcia na ekranie musi wynosić co najmniej średnicę kółka.
 */
internal fun minCropScale(bitmapWidth: Int, bitmapHeight: Int, circleRadius: Float): Float =
    maxOf(circleRadius * 2f / bitmapWidth, circleRadius * 2f / bitmapHeight)

/**
 * Ogranicza przesunięcie tak, żeby pod kółkiem nigdy nie było pustego tła.
 *
 * Lewa krawędź zdjęcia jest na ekranie w `centerX + offset.x − scale·W/2` i nie może wejść
 * za lewą krawędź kółka (`centerX − R`); analogicznie prawa. Oba warunki dają
 * `|offset.x| ≤ scale·W/2 − R`, i tak samo w pionie.
 */
internal fun clampCropOffset(
    offset: Offset,
    scale: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    circleRadius: Float,
): Offset {
    val maxX = (bitmapWidth * scale / 2f - circleRadius).coerceAtLeast(0f)
    val maxY = (bitmapHeight * scale / 2f - circleRadius).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Przelicza przesunięcie przy zmianie skali tak, żeby punkt zdjęcia pod `centroid` (środkiem
 * między palcami, we współrzędnych obszaru) został dokładnie tam, gdzie był.
 *
 * Z warunku, że ten sam piksel `p` ma po zmianie skali trafić w ten sam punkt ekranu, wychodzi
 * `offset' = d·(1 − k) + k·offset`, gdzie `k = newScale/oldScale`, a `d` to wektor od środka
 * obszaru do centroidu.
 *
 * Wcześniej przesunięcie zostawało nietknięte, co odpowiada `k = 1` w powyższym wzorze — przy
 * przybliżaniu punkt pod kółkiem przesuwał się o `(k − 1)·(offset − d)` i zdjęcie uciekało
 * z kadru tym szybciej, im dalej od środka był kadrowany kapsel.
 */
internal fun zoomedOffset(
    offset: Offset,
    oldScale: Float,
    newScale: Float,
    centroid: Offset,
    viewportCenter: Offset,
): Offset {
    val k = newScale / oldScale
    val d = centroid - viewportCenter
    return d * (1f - k) + offset * k
}

/**
 * Wycinek bitmapy widoczny w kółku. Piksel pod środkiem obszaru to `bitmapCenter − offset/scale`,
 * a kółko o promieniu `R` obejmuje wokół niego `R/scale` pikseli w każdą stronę.
 *
 * Zaciskanie do granic bitmapy jest zabezpieczeniem przed błędem zaokrągleń: przy poprawnym
 * [clampCropOffset] wycinek i tak mieści się w zdjęciu co do piksela.
 */
internal fun cropWindow(
    offset: Offset,
    scale: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    circleRadius: Float,
): CropWindow {
    val halfSize = circleRadius / scale
    val centerX = bitmapWidth / 2f - offset.x / scale
    val centerY = bitmapHeight / 2f - offset.y / scale
    val left = (centerX - halfSize).roundToInt().coerceIn(0, bitmapWidth - 1)
    val top = (centerY - halfSize).roundToInt().coerceIn(0, bitmapHeight - 1)
    val size = (halfSize * 2f).roundToInt()
        .coerceAtMost(bitmapWidth - left)
        .coerceAtMost(bitmapHeight - top)
        .coerceAtLeast(1)
    return CropWindow(left, top, size)
}

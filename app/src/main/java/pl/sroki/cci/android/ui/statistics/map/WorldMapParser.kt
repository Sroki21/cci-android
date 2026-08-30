package pl.sroki.cci.android.ui.statistics.map

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Prostokąt `viewBox` z SVG — do skalowania mapy pod rozmiar Canvas. */
data class ViewBox(val minX: Float, val minY: Float, val width: Float, val height: Float)

/**
 * Zparsowana mapa świata: [viewBox] + ścieżki krajów (kod ISO alpha-2 lowercase -> [Path]).
 * Kraje wieloczęściowe (`<g id>` z wieloma `<path>`) są scalone w jedną ścieżkę.
 *
 * Hit-test po tapnięciu działa przez [hitTestBitmap]: każdy kraj jest wrysowany unikalnym
 * kolorem-indeksem do jednej bitmapy w skali 1 piksel = 1 jednostka viewBox (bez antyaliasingu),
 * a [countryAt] po prostu odczytuje piksel pod dotknięciem. To ta sama rasteryzacja co przy
 * rysowaniu mapy (Skia Canvas.drawPath), więc jest ze 100% zgodna z tym co widać na ekranie —
 * w odróżnieniu od `android.graphics.Region.setPath()` (poprzednie podejście), które potrafiło
 * cicho zwracać pustą/błędną powierzchnię dla złożonych albo wieloczęściowych kształtów
 * (np. Wielka Brytania — wiele wysp; Finlandia — bardzo poszarpana linia brzegowa).
 */
class WorldMap(
    val viewBox: ViewBox,
    private val countryPaths: Map<String, Path>,
    private val hitTestBitmap: Bitmap,
    private val indexToIso: List<String>,
) {
    /**
     * Kopie ścieżek krajów, bezpieczne do mutacji przez wywołującego. [WorldMap] żyje jako
     * cache w [WorldMapParser] przez cały czas życia procesu — wystawienie oryginalnych
     * [Path] pozwoliłoby dowolnej transformacji in-place trwale zepsuć mapę dla wszystkich
     * kolejnych ekranów, bez żadnego sygnału o tym, że kontrakt „tylko do odczytu" złamano.
     */
    val countries: Map<String, Path> get() = countryPaths.mapValues { Path(it.value) }

    /**
     * Kod ISO kraju zawierającego punkt (x, y) w przestrzeni viewBox, lub null.
     *
     * @param tolerance promień (w jednostkach viewBox), w którym szukamy najbliższego kraju,
     *   gdy pod samym punktem jest morze. Patrz [hitTestIndex].
     */
    fun countryAt(x: Float, y: Float, tolerance: Float = 0f): String? {
        // Region tapnięcia pobrany JEDNYM getPixels() zamiast wywoływania getPixel() (JNI) osobno
        // dla każdego piksela w pętli fallbacku hitTestIndex — przy domyślnym zoomie tolerancja
        // dawała rząd 6000+ wywołań na jedno tapnięcie w ocean, realny jank na wątku UI.
        val px = ((x - viewBox.minX) * HIT_TEST_SCALE).toInt()
        val py = ((y - viewBox.minY) * HIT_TEST_SCALE).toInt()
        val promien = (tolerance * HIT_TEST_SCALE).toInt()
        val left = (px - promien).coerceIn(0, hitTestBitmap.width - 1)
        val top = (py - promien).coerceIn(0, hitTestBitmap.height - 1)
        val right = (px + promien).coerceIn(0, hitTestBitmap.width - 1)
        val bottom = (py + promien).coerceIn(0, hitTestBitmap.height - 1)
        val regionWidth = right - left + 1
        val regionHeight = bottom - top + 1
        val pixels = IntArray(regionWidth * regionHeight)
        hitTestBitmap.getPixels(pixels, 0, regionWidth, left, top, regionWidth, regionHeight)

        val index = hitTestIndex(x, y, viewBox, tolerance) { qx, qy ->
            if (qx < left || qx > right || qy < top || qy > bottom) {
                0
            } else {
                pixels[(qy - top) * regionWidth + (qx - left)] and 0x00FFFFFF
            }
        }
        if (index == 0) return null
        return indexToIso.getOrNull(index - 1)
    }
}

/**
 * Parsuje `assets/world_map.svg` (simple-world-map, CC BY-SA 3.0; id = ISO 3166-1 alpha-2).
 * Parsowanie poza main thread, wynik cache'owany — plik jest statyczny.
 */
@Singleton
class WorldMapParser @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: WorldMap? = null

    // @Volatile daje widoczność, nie atomowość: dwa równoległe wejścia na mapę mijały się między
    // sprawdzeniem a zapisem i parsowały SVG dwa razy, budując dwie bitmapy po kilka MB.
    private val mutex = Mutex()

    suspend fun load(): WorldMap {
        cached?.let { return it }
        return withContext(Dispatchers.Default) {
            mutex.withLock { cached ?: parse().also { cached = it } }
        }
    }

    private fun parse(): WorldMap {
        val countries = LinkedHashMap<String, Path>()
        var viewBox = ViewBox(0f, 0f, 0f, 0f)
        var groupId: String? = null

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        context.assets.open(ASSET_NAME).use { stream ->
            parser.setInput(stream, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "svg" -> parser.getAttributeValue(null, "viewBox")
                            ?.let { viewBox = it.toViewBox() }
                        "g" -> groupId = parser.getAttributeValue(null, "id")
                        "path" -> {
                            val d = parser.getAttributeValue(null, "d")
                            val iso = parser.getAttributeValue(null, "id") ?: groupId
                            if (d != null && iso != null && !iso.startsWith("_")) {
                                addPath(countries, iso.lowercase(), d)
                            }
                        }
                    }
                    // Brak zagnieżdżonych grup z id — reset do warstwy <g> bez id wystarcza.
                    XmlPullParser.END_TAG -> if (parser.name == "g") groupId = null
                }
                event = parser.next()
            }
        }
        val (bitmap, indexToIso) = buildHitTestBitmap(countries, viewBox)
        return WorldMap(viewBox, countries, bitmap, indexToIso)
    }

    /**
     * Rysuje każdy kraj unikalnym kolorem-indeksem (1..N, spakowanym w RGB) do bitmapy
     * bez antyaliasingu, w skali [HIT_TEST_SCALE] px na jednostkę viewBox. Indeks 0 (czarny)
     * = brak kraju.
     *
     * Skala 1:1 dawała bitmapę 784×458 dla całego świata, więc państwa mniejsze od piksela
     * (Malta, Singapur, Luksemburg, Czarnogóra, Cypr, Liban) nie zamalowywały niczego i nie dało
     * się ich tapnąć nawet po maksymalnym przybliżeniu. Podwojenie skali kosztuje ~5,7 MB zamiast
     * ~1,4 MB i samo w sobie nie wystarcza — dlatego druga pętla dorysowuje kropkę o średnicy
     * [MIN_COUNTRY_PX] każdemu krajowi, którego cała ścieżka jest mniejsza niż ta kropka.
     * Idzie ona PO wszystkich krajach, żeby większy sąsiad jej nie zamalował.
     */
    private fun buildHitTestBitmap(
        countries: Map<String, Path>,
        viewBox: ViewBox,
    ): Pair<Bitmap, List<String>> {
        val width = (viewBox.width * HIT_TEST_SCALE).toInt().coerceAtLeast(1)
        val height = (viewBox.height * HIT_TEST_SCALE).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        // Kolejność ma znaczenie: scale, potem translate daje px = (v − min) · HIT_TEST_SCALE.
        canvas.scale(HIT_TEST_SCALE.toFloat(), HIT_TEST_SCALE.toFloat())
        canvas.translate(-viewBox.minX, -viewBox.minY)
        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        val indexToIso = ArrayList<String>(countries.size)
        countries.entries.forEachIndexed { i, (iso, path) ->
            paint.color = indexColor(i + 1) // 0 zarezerwowane pod "brak kraju"
            canvas.drawPath(path, paint)
            indexToIso += iso
        }

        val bounds = RectF()
        val minSize = MIN_COUNTRY_PX.toFloat() / HIT_TEST_SCALE
        countries.values.forEachIndexed { i, path ->
            path.computeBounds(bounds, true)
            if (bounds.width() < minSize || bounds.height() < minSize) {
                paint.color = indexColor(i + 1)
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), minSize / 2f, paint)
            }
        }
        return bitmap to indexToIso
    }

    private fun indexColor(index: Int): Int =
        Color.argb(0xFF, (index shr 16) and 0xFF, (index shr 8) and 0xFF, index and 0xFF)

    private fun addPath(into: LinkedHashMap<String, Path>, iso: String, pathData: String) {
        val sub = try {
            PathParser.createPathFromPathData(pathData)
        } catch (e: Exception) {
            // Bez tego kraj z uszkodzoną ścieżką po prostu znikał z mapy — bez śladu w logu.
            Log.w("CCI_MAP", "nie udało się sparsować ścieżki kraju $iso: ${e.message}")
            return
        }
        into.getOrPut(iso) { Path() }.addPath(sub)
    }

    private fun String.toViewBox(): ViewBox {
        val p = trim().split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
        return if (p.size == 4) ViewBox(p[0], p[1], p[2], p[3]) else ViewBox(0f, 0f, 0f, 0f)
    }

    private companion object {
        const val ASSET_NAME = "world_map.svg"
    }
}

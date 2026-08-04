package pl.sroki.cci.android.ui.statistics.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    val countries: Map<String, Path>,
    private val hitTestBitmap: Bitmap,
    private val indexToIso: List<String>,
) {
    /** Kod ISO kraju zawierającego punkt (x, y) w przestrzeni viewBox, lub null. */
    fun countryAt(x: Float, y: Float): String? {
        val px = (x - viewBox.minX).toInt()
        val py = (y - viewBox.minY).toInt()
        if (px !in 0 until hitTestBitmap.width || py !in 0 until hitTestBitmap.height) return null
        val packed = hitTestBitmap.getPixel(px, py) and 0x00FFFFFF
        if (packed == 0) return null
        return indexToIso.getOrNull(packed - 1)
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

    suspend fun load(): WorldMap {
        cached?.let { return it }
        return withContext(Dispatchers.Default) {
            cached ?: parse().also { cached = it }
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
     * bez antyaliasingu, w skali 1px = 1 jednostka viewBox. Indeks 0 (czarny) = brak kraju.
     */
    private fun buildHitTestBitmap(
        countries: Map<String, Path>,
        viewBox: ViewBox,
    ): Pair<Bitmap, List<String>> {
        val width = viewBox.width.toInt().coerceAtLeast(1)
        val height = viewBox.height.toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-viewBox.minX, -viewBox.minY)
        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        val indexToIso = ArrayList<String>(countries.size)
        countries.entries.forEachIndexed { i, (iso, path) ->
            val index = i + 1 // 0 zarezerwowane pod "brak kraju"
            paint.color = Color.argb(0xFF, (index shr 16) and 0xFF, (index shr 8) and 0xFF, index and 0xFF)
            canvas.drawPath(path, paint)
            indexToIso += iso
        }
        return bitmap to indexToIso
    }

    private fun addPath(into: LinkedHashMap<String, Path>, iso: String, pathData: String) {
        val sub = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull() ?: return
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

package pl.sroki.cci.android.ui.statistics.map

import android.content.Context
import android.graphics.Path
import android.graphics.Region
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
 * [regions] to te same kraje jako [Region] w przestrzeni viewBox — do hit-testu po tapnięciu.
 */
data class WorldMap(
    val viewBox: ViewBox,
    val countries: Map<String, Path>,
    val regions: Map<String, Region>,
) {
    /** Kod ISO kraju zawierającego punkt (x, y) w przestrzeni viewBox, lub null. */
    fun countryAt(x: Float, y: Float): String? {
        val px = x.toInt()
        val py = y.toInt()
        return regions.entries.firstOrNull { it.value.contains(px, py) }?.key
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
        val countries = HashMap<String, Path>()
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
        return WorldMap(viewBox, countries, buildRegions(countries))
    }

    private fun buildRegions(countries: Map<String, Path>): Map<String, Region> {
        val bounds = android.graphics.RectF()
        return countries.mapValues { (_, path) ->
            path.computeBounds(bounds, true)
            val clip = Region(
                Math.floor(bounds.left.toDouble()).toInt(),
                Math.floor(bounds.top.toDouble()).toInt(),
                Math.ceil(bounds.right.toDouble()).toInt(),
                Math.ceil(bounds.bottom.toDouble()).toInt(),
            )
            Region().apply { setPath(path, clip) }
        }
    }

    private fun addPath(into: HashMap<String, Path>, iso: String, pathData: String) {
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

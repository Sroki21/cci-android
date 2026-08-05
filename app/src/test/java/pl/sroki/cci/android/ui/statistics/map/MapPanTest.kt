package pl.sroki.cci.android.ui.statistics.map

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wymiary z realnego przypadku: viewBox world_map.svg to 784.077 x 458.627, obszar mapy na
 * Pixelu 8 to 1080 x 2000 px. Dopasowanie idzie po szerokości (fitScale ≈ 1.377), więc mapa
 * zajmuje pionowo ≈ 632 px i zostaje ≈ 684 px pustego obszaru nad i pod nią. To ten fitOffset
 * rozjeżdżał poprzedni, symetryczny wzór — treść wypełnia pion dopiero przy zoomie ≈ 3.17x.
 */
class MapPanTest {

    private val viewportW = 1080f
    private val viewportH = 2000f
    private val fit = run {
        val vbW = 784.077f
        val vbH = 458.627f
        val scale = minOf(viewportW / vbW, viewportH / vbH)
        MapFit(
            scale = scale,
            offset = Offset((viewportW - vbW * scale) / 2f, (viewportH - vbH * scale) / 2f),
            contentWidth = vbW * scale,
            contentHeight = vbH * scale,
        )
    }

    private fun clamp(offset: Offset, scale: Float) =
        clampMapOffset(offset, scale, viewportW, viewportH, fit)

    private fun top(offset: Offset, scale: Float) = offset.y + scale * fit.offset.y
    private fun bottom(offset: Offset, scale: Float) = top(offset, scale) + scale * fit.contentHeight

    @Test
    fun `bez zoomu mapa zostaje tam, gdzie postawilo ja dopasowanie`() {
        val result = clamp(Offset(300f, -700f), scale = 1f)

        assertEquals(0f, result.x, 0.5f)
        assertEquals(0f, result.y, 0.5f)
    }

    @Test
    fun `dopoki mapa nie wypelnia pionu, zostaje wysrodkowana`() {
        val scale = 2f
        assertTrue("przy 2x mapa nadal nie wypelnia pionu", scale * fit.contentHeight < viewportH)

        val wDol = clamp(Offset(0f, 5000f), scale)
        val wGore = clamp(Offset(0f, -5000f), scale)

        assertEquals(wDol.y, wGore.y, 0.5f)
        val srodek = (top(wDol, scale) + bottom(wDol, scale)) / 2f
        assertEquals(viewportH / 2f, srodek, 0.5f)
    }

    @Test
    fun `gdy mapa wypelnia pion, krawedzie nie wpuszczaja pustki`() {
        val scale = 4f
        assertTrue("przy 4x mapa wypelnia pion", scale * fit.contentHeight > viewportH)

        assertEquals(0f, top(clamp(Offset(0f, 5000f), scale), scale), 0.5f)
        assertEquals(viewportH, bottom(clamp(Offset(0f, -5000f), scale), scale), 0.5f)
    }

    @Test
    fun `poprzedni wzor pozwalal odciac gore mapy i zostawic pusty dol`() {
        val scale = 2f
        // Dolna granica poprzedniego wzoru dla osi pionowej: -(s-1)*viewport.
        val stareOffset = Offset(0f, -(scale - 1f) * viewportH)

        // Przy tym przesunięciu mapa wychodziła ponad górną krawędź, a pod nią zostawał
        // pas pustego oceanu grubości ponad połowy ekranu.
        assertTrue("gora mapy powinna uciec ponad obszar", top(stareOffset, scale) < -500f)
        assertTrue("pod mapa powinna zostac pustka", bottom(stareOffset, scale) < viewportH - 1000f)

        // Nowy clamp sprowadza to samo przesunięcie z powrotem do wyśrodkowania.
        val poprawione = clamp(stareOffset, scale)
        assertEquals(viewportH / 2f, (top(poprawione, scale) + bottom(poprawione, scale)) / 2f, 0.5f)
    }

    @Test
    fun `os dopasowania zachowuje sie tak jak dotychczas`() {
        val scale = 3f
        // Na osi, którą dopasowanie wypełnia co do piksela, poprawny zakres to
        // [-(s-1)*viewport, 0] — dokładnie to, co liczył poprzedni wzór. Ta oś nie miała
        // błędu i nie może się zmienić.
        assertEquals(0f, clamp(Offset(1000f, 0f), scale).x, 0.5f)
        assertEquals(-(scale - 1f) * viewportW, clamp(Offset(-9999f, 0f), scale).x, 0.5f)
    }
}

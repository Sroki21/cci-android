package pl.sroki.cci.android.ui.catalog.picturesearch

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wymiary z realnego przypadku: zdjęcie po podpróbkowaniu 1536 x 2048, obszar kadrowania na
 * Pixelu 8 to 1080 x 2400 px, kółko ma promień 0.42 · min(bok) ≈ 453 px.
 */
class CropGeometryTest {

    private val bitmapW = 1536
    private val bitmapH = 2048
    private val viewportW = 1080f
    private val viewportH = 2400f
    private val radius = minOf(viewportW, viewportH) * 0.42f
    private val viewportCenter = Offset(viewportW / 2f, viewportH / 2f)

    private fun clamp(offset: Offset, scale: Float) =
        clampCropOffset(offset, scale, bitmapW, bitmapH, radius)

    /** Piksel zdjęcia, który przy danym przesunięciu i skali leży pod punktem `screen`. */
    private fun pixelUnder(screen: Offset, offset: Offset, scale: Float): Offset {
        val bitmapCenter = Offset(bitmapW / 2f, bitmapH / 2f)
        return bitmapCenter + (screen - viewportCenter - offset) / scale
    }

    @Test
    fun `najmniejsza skala wypelnia kolko w obu osiach`() {
        val scale = minCropScale(bitmapW, bitmapH, radius)

        assertEquals(2f * radius, bitmapW * scale, 0.5f)
        assertTrue("wyzszy bok ma zapas", bitmapH * scale >= 2f * radius)
    }

    @Test
    fun `przesuniecie nie wpuszcza pustki pod kolko`() {
        val scale = 0.6f
        val doKonca = clamp(Offset(9999f, 9999f), scale)

        // Lewa krawędź zdjęcia dosuwana maksymalnie w prawo ma się zatrzymać na krawędzi kółka.
        val lewaKrawedzZdjecia = viewportCenter.x + doKonca.x - bitmapW * scale / 2f
        assertEquals(viewportCenter.x - radius, lewaKrawedzZdjecia, 0.5f)

        val gornaKrawedzZdjecia = viewportCenter.y + doKonca.y - bitmapH * scale / 2f
        assertEquals(viewportCenter.y - radius, gornaKrawedzZdjecia, 0.5f)
    }

    @Test
    fun `przyblizanie zostawia kadrowany punkt pod kolkiem`() {
        val scale = 0.8f
        val offset = Offset(-120f, 260f)
        val przed = pixelUnder(viewportCenter, offset, scale)

        val nowaSkala = scale * 1.6f
        val po = zoomedOffset(offset, scale, nowaSkala, viewportCenter, viewportCenter)

        val poZoomie = pixelUnder(viewportCenter, po, nowaSkala)
        assertEquals(przed.x, poZoomie.x, 0.5f)
        assertEquals(przed.y, poZoomie.y, 0.5f)
    }

    @Test
    fun `poprzednie zachowanie wypychalo kadrowany punkt z kolka`() {
        val scale = 0.8f
        val offset = Offset(-120f, 260f)
        val nowaSkala = scale * 1.6f

        // Wcześniej przesunięcie zostawało nietknięte przy zmianie skali.
        val przed = pixelUnder(viewportCenter, offset, scale)
        val poStaremu = pixelUnder(viewportCenter, offset, nowaSkala)

        val przesuniecieWPikselach = (poStaremu - przed).getDistance()
        assertTrue(
            "kadrowany punkt uciekal o setki pikseli zdjecia",
            przesuniecieWPikselach > 100f,
        )
    }

    @Test
    fun `przyblizanie palcami trzyma punkt miedzy palcami`() {
        val scale = 0.9f
        val offset = Offset(80f, -40f)
        val centroid = Offset(viewportW * 0.3f, viewportH * 0.7f)
        val przed = pixelUnder(centroid, offset, scale)

        val nowaSkala = scale * 2.5f
        val po = zoomedOffset(offset, scale, nowaSkala, centroid, viewportCenter)

        val poZoomie = pixelUnder(centroid, po, nowaSkala)
        assertEquals(przed.x, poZoomie.x, 0.5f)
        assertEquals(przed.y, poZoomie.y, 0.5f)
    }

    @Test
    fun `wycinek odpowiada temu, co widac w kolku`() {
        val scale = 1.2f
        val offset = clamp(Offset(150f, -90f), scale)

        val okno = cropWindow(offset, scale, bitmapW, bitmapH, radius)

        val podSrodkiem = pixelUnder(viewportCenter, offset, scale)
        assertEquals(podSrodkiem.x, okno.left + okno.size / 2f, 1f)
        assertEquals(podSrodkiem.y, okno.top + okno.size / 2f, 1f)
        assertEquals(2f * radius / scale, okno.size.toFloat(), 1f)
    }

    @Test
    fun `podprobkowanie sprowadza zdjecie z aparatu do rozsadnego rozmiaru`() {
        // 50 Mpx (8160 x 6120) to ok. 200 MB w ARGB_8888 — tyle wchodziło do pamięci wcześniej.
        val sample = sampleSizeFor(8160, 6120, maxDimension = 2048)

        assertEquals(4, sample)
        assertTrue("dluzszy bok po podprobkowaniu miesci sie w limicie", 8160 / sample <= 2048)
        // Krok w dół to zawsze potęga dwójki, więc sprawdzamy, że nie zeszliśmy o jeden za dużo.
        assertTrue("zostaje zapas ponad wysylany wycinek 800 px", 8160 / sample > 1024)
    }

    @Test
    fun `male zdjecie nie jest podprobkowane`() {
        assertEquals(1, sampleSizeFor(1200, 900, maxDimension = 2048))
        assertEquals(1, sampleSizeFor(2048, 2048, maxDimension = 2048))
    }

    @Test
    fun `wycinek przy skrajnym przesunieciu miesci sie w zdjeciu`() {
        val scale = minCropScale(bitmapW, bitmapH, radius)

        for (kierunek in listOf(Offset(9999f, 9999f), Offset(-9999f, -9999f), Offset(9999f, -9999f))) {
            val okno = cropWindow(clamp(kierunek, scale), scale, bitmapW, bitmapH, radius)

            assertTrue("left w zakresie ($kierunek)", okno.left >= 0)
            assertTrue("top w zakresie ($kierunek)", okno.top >= 0)
            assertTrue("prawa krawedz w zdjeciu ($kierunek)", okno.left + okno.size <= bitmapW)
            assertTrue("dolna krawedz w zdjeciu ($kierunek)", okno.top + okno.size <= bitmapH)
        }
    }
}

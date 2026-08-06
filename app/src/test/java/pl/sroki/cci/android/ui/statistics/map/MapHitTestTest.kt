package pl.sroki.cci.android.ui.statistics.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hit-test mapy miał stałą rozdzielczość: bitmapa 1 px = 1 jednostka viewBox, podczas gdy
 * rysowanie skalowało się z zoomem wektorowo. Kraj mniejszy niż piksel nie zamalowywał niczego,
 * więc Malty, Singapuru czy Luksemburga nie dało się tapnąć nawet po maksymalnym przybliżeniu.
 *
 * Testy liczą samą geometrię, na sztucznej „bitmapie" — Bitmapy nie da się stworzyć na JVM.
 */
class MapHitTestTest {

    private val viewBox = ViewBox(minX = 30.767f, minY = 241.591f, width = 784.077f, height = 458.627f)

    /** Mapa: pojedynczy piksel kraju nr 7 w podanym miejscu, reszta to morze. */
    private fun jedenPiksel(px: Int, py: Int): (Int, Int) -> Int =
        { x, y -> if (x == px && y == py) 7 else 0 }

    private fun pikselDla(x: Float, y: Float): Pair<Int, Int> =
        ((x - viewBox.minX) * HIT_TEST_SCALE).toInt() to ((y - viewBox.minY) * HIT_TEST_SCALE).toInt()

    @Test
    fun `trafienie w kraj zwraca jego indeks`() {
        val (px, py) = pikselDla(100f, 300f)

        val wynik = hitTestIndex(100f, 300f, viewBox, indexAt = jedenPiksel(px, py))

        assertEquals(7, wynik)
    }

    @Test
    fun `trafienie w morze bez tolerancji zwraca zero`() {
        val wynik = hitTestIndex(100f, 300f, viewBox, indexAt = { _, _ -> 0 })

        assertEquals(0, wynik)
    }

    /** Sedno M1: kraj wielkości jednego piksela musi być trafialny z sąsiedniego punktu. */
    @Test
    fun `tolerancja znajduje kraj obok punktu tapniecia`() {
        val (px, py) = pikselDla(100f, 300f)
        // Kraj leży 4 piksele bitmapy dalej — użytkownik chybił o 2 jednostki viewBox.
        val mapa = jedenPiksel(px + 4, py)

        val bezTolerancji = hitTestIndex(100f, 300f, viewBox, tolerance = 0f, indexAt = mapa)
        val zTolerancja = hitTestIndex(100f, 300f, viewBox, tolerance = 3f, indexAt = mapa)

        assertEquals(0, bezTolerancji)
        assertEquals(7, zTolerancja)
    }

    @Test
    fun `tolerancja nie siega poza swoj promien`() {
        val (px, py) = pikselDla(100f, 300f)
        // 10 pikseli bitmapy = 5 jednostek viewBox, tolerancja 3 jednostki to za mało.
        val wynik = hitTestIndex(100f, 300f, viewBox, tolerance = 3f, indexAt = jedenPiksel(px + 10, py))

        assertEquals(0, wynik)
    }

    /** Tolerancja jest promieniem, nie kwadratem — róg kwadratu leży dalej niż jego bok. */
    @Test
    fun `tolerancja dziala jednakowo we wszystkich kierunkach`() {
        val (px, py) = pikselDla(100f, 300f)
        val promienPx = (3f * HIT_TEST_SCALE).toInt()

        // Dokładnie na krawędzi koła w pionie i poziomie — trafia.
        assertEquals(7, hitTestIndex(100f, 300f, viewBox, 3f, jedenPiksel(px + promienPx, py)))
        assertEquals(7, hitTestIndex(100f, 300f, viewBox, 3f, jedenPiksel(px, py + promienPx)))
        // Róg kwadratu o tym samym boku leży poza kołem — nie trafia.
        assertEquals(0, hitTestIndex(100f, 300f, viewBox, 3f, jedenPiksel(px + promienPx, py + promienPx)))
    }

    @Test
    fun `przy dwoch krajach w zasiegu wygrywa blizszy`() {
        val (px, py) = pikselDla(100f, 300f)
        val mapa = { x: Int, y: Int ->
            when {
                x == px + 2 && y == py -> 7
                x == px + 5 && y == py -> 9
                else -> 0
            }
        }

        assertEquals(7, hitTestIndex(100f, 300f, viewBox, tolerance = 5f, indexAt = mapa))
    }

    @Test
    fun `punkt poza mapa nie trafia w nic`() {
        // indexAt zwraca 0 poza zakresem bitmapy — tak jak robi to WorldMap.
        val wynik = hitTestIndex(-500f, -500f, viewBox, tolerance = 5f, indexAt = { _, _ -> 0 })

        assertEquals(0, wynik)
    }

    /** Piksel, o który hitTestIndex faktycznie zapytał dla danego punktu viewBox. */
    private fun odpytanyPiksel(x: Float, y: Float): Pair<Int, Int> {
        var zapisany = -1 to -1
        hitTestIndex(x, y, viewBox) { px, py -> zapisany = px to py; 0 }
        return zapisany
    }

    /**
     * Skala musi być uwzględniona przy przeliczaniu punktu na piksel — przy pomyłce o ten czynnik
     * trafienia rozjeżdżałyby się o połowę mapy. Test porównuje przesunięcia zamiast bezwzględnych
     * pozycji, bo `(30.767f + 10f) − 30.767f` to w Float 9.999998, więc konkretny piksel zależy
     * od zaokrąglenia — a to przy tolerancji rzędu kilkunastu pikseli nie ma znaczenia.
     */
    @Test
    fun `jednostka viewBox to HIT_TEST_SCALE pikseli`() {
        val (px0, py0) = odpytanyPiksel(viewBox.minX + 100f, viewBox.minY + 100f)
        val (px1, py1) = odpytanyPiksel(viewBox.minX + 101f, viewBox.minY + 101f)

        assertEquals(HIT_TEST_SCALE, px1 - px0)
        assertEquals(HIT_TEST_SCALE, py1 - py0)
    }

    @Test
    fun `punkt w lewym gornym rogu viewBox to piksel zero`() {
        assertEquals(0 to 0, odpytanyPiksel(viewBox.minX, viewBox.minY))
    }
}

package pl.sroki.cci.android.ui.statistics.map

/**
 * Geometria hit-testu mapy, wydzielona z [WorldMap], żeby dała się policzyć bez Bitmapy
 * (patrz MapHitTestTest) — tak jak CropGeometry przy kadrowaniu.
 *
 * Bitmapa hit-testu jest rasteryzowana w skali [HIT_TEST_SCALE] pikseli na jednostkę viewBox,
 * a punkt tapnięcia przychodzi w jednostkach viewBox:
 *
 *     px = floor((x − viewBox.minX) · HIT_TEST_SCALE)
 *     py = floor((y − viewBox.minY) · HIT_TEST_SCALE)
 *
 * Samo odczytanie piksela nie wystarcza. Rysowanie mapy skaluje się z zoomem wektorowo, a bitmapa
 * ma stałą rozdzielczość — kraj mniejszy niż piksel nie zamaluje ani jednego (Skia zapala piksel
 * tylko wtedy, gdy jego środek leży w ścieżce, a hit-test rysuje bez antyaliasingu). Użytkownik
 * przybliżał Maltę, widział ją wyraźnie, tapał i trafiał w morze. Dlatego dochodzi [tolerance]:
 * promień w jednostkach viewBox, w którym szukamy najbliższego kraju, gdy pod palcem jest woda.
 * Wywołujący liczy go ze stałej odległości na ekranie podzielonej przez aktualną skalę, więc
 * po przybliżeniu tolerancja maleje razem z tym, jak precyzyjnie da się celować.
 */
const val HIT_TEST_SCALE = 2

/** Minimalna średnica (w pikselach bitmapy), jaką dostaje kraj zbyt mały, by cokolwiek zamalować. */
const val MIN_COUNTRY_PX = 3

/**
 * @param indexAt indeks kraju pod pikselem bitmapy (1..N), 0 dla morza i poza zakresem.
 * @return indeks trafionego kraju albo 0.
 */
fun hitTestIndex(
    x: Float,
    y: Float,
    viewBox: ViewBox,
    tolerance: Float = 0f,
    indexAt: (px: Int, py: Int) -> Int,
): Int {
    val px = ((x - viewBox.minX) * HIT_TEST_SCALE).toInt()
    val py = ((y - viewBox.minY) * HIT_TEST_SCALE).toInt()
    val dokladny = indexAt(px, py)
    if (dokladny != 0) return dokladny

    val promien = (tolerance * HIT_TEST_SCALE).toInt()
    if (promien <= 0) return 0

    // Najbliższy kraj w promieniu — kwadrat przeszukiwania przycięty do koła, żeby tolerancja
    // była jednakowa we wszystkich kierunkach. Remis rozstrzyga kolejność skanowania; przy
    // promieniu rzędu kilkunastu pikseli to i tak sąsiedztwo jednego kraju.
    var najlepszy = 0
    var najblizszy = Int.MAX_VALUE
    for (dy in -promien..promien) {
        for (dx in -promien..promien) {
            val kwadratOdleglosci = dx * dx + dy * dy
            if (kwadratOdleglosci > promien * promien || kwadratOdleglosci >= najblizszy) continue
            val indeks = indexAt(px + dx, py + dy)
            if (indeks != 0) {
                najlepszy = indeks
                najblizszy = kwadratOdleglosci
            }
        }
    }
    return najlepszy
}

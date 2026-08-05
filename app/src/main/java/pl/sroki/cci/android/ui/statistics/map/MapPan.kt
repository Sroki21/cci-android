package pl.sroki.cci.android.ui.statistics.map

import androidx.compose.ui.geometry.Offset

/**
 * Dopasowanie mapy do widocznego obszaru: skala i przesunięcie liczone raz na rozmiar Canvas,
 * używane zarówno przy rysowaniu, jak i przy hit-teście oraz ograniczaniu przesuwu.
 */
internal data class MapFit(
    val scale: Float,
    val offset: Offset,
    val contentWidth: Float,
    val contentHeight: Float,
)

/**
 * Ogranicza przesunięcie użytkownika tak, żeby mapa nie odjeżdżała poza widoczny obszar.
 *
 * Rysowanie odwzorowuje punkt viewBox na ekran wzorem
 * `screen = userOffset + userScale · (fitOffset + fitScale · (v − viewBox.min))`,
 * więc treść zajmuje na ekranie przedział `[userOffset + userScale·fitOffset, … + userScale·content]`.
 * Warunek "brak luki przy żadnej krawędzi" daje granice zależne od `fitOffset` i rozmiaru treści.
 *
 * Poprzedni wzór — symetryczne `[-(s−1)·viewport, 0]` — jest tym samym wyłącznie przy
 * `fitOffset = 0`, czyli na osi, którą dopasowanie wypełnia co do piksela. Na drugiej osi
 * (dla mapy świata na telefonie: pionowej, gdzie `fitOffset.y` to setki pikseli) był o
 * `2·s·fitOffset` za szeroki i pozwalał zepchnąć mapę na sam skraj ekranu.
 */
internal fun clampMapOffset(
    offset: Offset,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    fit: MapFit,
): Offset = Offset(
    clampAxis(offset.x, scale, viewportWidth, fit.offset.x, fit.contentWidth),
    clampAxis(offset.y, scale, viewportHeight, fit.offset.y, fit.contentHeight),
)

private fun clampAxis(
    value: Float,
    scale: Float,
    viewport: Float,
    fitOffset: Float,
    content: Float,
): Float {
    val scaled = content * scale
    // Przesunięcie, przy którym lewa (górna) krawędź treści dotyka krawędzi obszaru.
    val anchor = -scale * fitOffset
    return if (scaled >= viewport) {
        value.coerceIn(anchor + viewport - scaled, anchor)
    } else {
        // Treść węższa (niższa) niż obszar — nie ma czego przesuwać, trzymamy ją wyśrodkowaną.
        anchor + (viewport - scaled) / 2f
    }
}

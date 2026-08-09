package pl.sroki.cci.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pilnuje, że `android:windowBackground` (res/values/colors.xml i res/values-night/colors.xml)
 * odpowiada tłu, którym Compose maluje aplikację.
 *
 * Tło okna widać przez ułamek sekundy przy zimnym starcie i przy odtworzeniu okna po powrocie
 * z tła — zanim powstanie pierwsza klatka Compose. Gdy oba kolory się rozjeżdżają, użytkownik
 * dostaje w tym momencie błysk obcym kolorem; przy jasnym motywie aktywności i systemowym trybie
 * ciemnym był to pełnoekranowy biały prostokąt na sekundę.
 *
 * Testu nie da się oprzeć o realny odczyt XML bez Robolectrica, więc wartości są tu powtórzone —
 * po to, by podbicie Material3 ze zmienionym domyślnym `background` wywaliło ten test zamiast
 * po cichu przywrócić błysk.
 */
class WindowBackgroundColorTest {

    @Test
    fun `jasne tlo okna zgadza sie z kolorem tla Compose`() {
        assertEquals(argb("#FFFEF7FF"), lightColorScheme().background.argb())
    }

    @Test
    fun `ciemne tlo okna zgadza sie z kolorem tla Compose`() {
        assertEquals(argb("#FF141218"), darkColorScheme().background.argb())
    }

    /** Kolor Compose spakowany jest w górnych 32 bitach [Color.value]. */
    private fun Color.argb(): Long = (value shr 32).toLong() and 0xFFFFFFFFL

    private fun argb(hex: String): Long = hex.removePrefix("#").toLong(16)
}

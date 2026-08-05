package pl.sroki.cci.android.model.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Statusy krążyły wcześniej jako Stringi i zbiór „oflagowanych" był wyliczany w kilku miejscach
 * niezależnie. Jedno się rozjechało: BindersScreen nie znało `producer_removed`, więc kapsel
 * z usuniętym producentem liczył się do odznaki i pojawiał w Weryfikacji, ale w Klaserach
 * nie był podświetlany.
 */
class CatalogStatusTest {

    @Test
    fun `producer_removed jest oflagowany`() {
        assertTrue(CatalogStatus.PRODUCER_REMOVED.isFlagged)
    }

    @Test
    fun `oflagowane jest wszystko poza ok i unknown`() {
        val oflagowane = CatalogStatus.entries.filter { it.isFlagged }.toSet()
        assertEquals(
            setOf(
                CatalogStatus.UPDATED,
                CatalogStatus.SWAPPED,
                CatalogStatus.MISSING,
                CatalogStatus.PRODUCER_REMOVED
            ),
            oflagowane
        )
        assertFalse(CatalogStatus.OK.isFlagged)
        assertFalse(CatalogStatus.UNKNOWN.isFlagged)
    }

    @Test
    fun `raw odpowiada wartosciom utrwalanym w bazie`() {
        // Te napisy siedzą w kolumnie cap_cache.catalog_status istniejących instalacji —
        // zmiana któregokolwiek unieważnia zapisane wyniki weryfikacji.
        assertEquals("unknown", CatalogStatus.UNKNOWN.raw)
        assertEquals("ok", CatalogStatus.OK.raw)
        assertEquals("updated", CatalogStatus.UPDATED.raw)
        assertEquals("swapped", CatalogStatus.SWAPPED.raw)
        assertEquals("missing", CatalogStatus.MISSING.raw)
        assertEquals("producer_removed", CatalogStatus.PRODUCER_REMOVED.raw)
    }

    @Test
    fun `from odwzorowuje kazdy raz i nieznane mapuje na UNKNOWN`() {
        CatalogStatus.entries.forEach { status ->
            assertEquals(status, CatalogStatus.from(status.raw))
        }
        assertEquals(CatalogStatus.UNKNOWN, CatalogStatus.from(null))
        assertEquals(CatalogStatus.UNKNOWN, CatalogStatus.from("cos_nowego"))
    }
}

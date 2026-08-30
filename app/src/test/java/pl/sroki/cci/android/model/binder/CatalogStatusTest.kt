package pl.sroki.cci.android.model.binder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sedno testu: napisy w `raw` są kontraktem z bazą — siedzą w kolumnie `cap_cache.catalog_status`
 * istniejących instalacji, więc zmiana któregokolwiek unieważnia zapisane wyniki weryfikacji.
 *
 * Zbiór „oflagowanych" nie jest tu sprawdzany, bo nie ma go w tym typie: definiuje go negatywny
 * warunek SQL w `CapCacheDao`, a UI rozbiera status wyczerpującym `when` bez `else`.
 */
class CatalogStatusTest {

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

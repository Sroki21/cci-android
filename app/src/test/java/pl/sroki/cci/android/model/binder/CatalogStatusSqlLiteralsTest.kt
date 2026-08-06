package pl.sroki.cci.android.model.binder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `CapCacheDao` definiuje zbiór oflagowanych kapsli negatywnie: `catalog_status NOT IN
 * ('ok', 'unknown')`. Te literały są sprzężone z serializacją [CatalogStatus], ale SQL siedzi
 * w stringu — zmiana nazwy w enumie nie wywołałaby błędu kompilacji, tylko po cichu wyłączyła
 * odznakę rozjazdów i ekran weryfikacji. Ten test jest jedynym miejscem, które to łączy.
 */
class CatalogStatusSqlLiteralsTest {

    @Test
    fun `literaly z zapytan DAO odpowiadaja wartosciom enuma`() {
        assertEquals("ok", CatalogStatus.OK.raw)
        assertEquals("unknown", CatalogStatus.UNKNOWN.raw)
    }

    /** Każdy pozostały status MUSI wpadać do zbioru oflagowanych, inaczej zniknie z ekranu. */
    @Test
    fun `pozostale statusy sa poza zbiorem ok-unknown`() {
        val poza = CatalogStatus.entries.filter { it.raw !in listOf("ok", "unknown") }

        assertEquals(
            setOf(CatalogStatus.SWAPPED, CatalogStatus.UPDATED, CatalogStatus.MISSING, CatalogStatus.PRODUCER_REMOVED),
            poza.toSet()
        )
    }
}

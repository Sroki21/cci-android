package pl.sroki.cci.android.data

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.sroki.cci.android.model.Cap

/**
 * Powtórzony kapsel na styku stron wywalał listę: klucze w LazyPagingItems budujemy z cap.id,
 * a Compose wymaga unikalnych — drugi taki sam dawał `Key … was already used`.
 */
class PageDeduplicatorTest {

    private fun cap(id: Long) = Cap(
        id = id, country = "Polska", product = "", liner = "", purpose = "", imageUrl = ""
    )

    @Test
    fun `druga strona nie powtarza kapsli z pierwszej`() {
        val dedup = PageDeduplicator()

        val strona1 = dedup.odsiej(listOf(cap(1), cap(2), cap(3)))
        val strona2 = dedup.odsiej(listOf(cap(3), cap(4)))

        assertEquals(listOf(1L, 2L, 3L), strona1.map { it.id })
        assertEquals(listOf(4L), strona2.map { it.id })
    }

    @Test
    fun `duplikat wewnatrz jednej strony tez odpada`() {
        val dedup = PageDeduplicator()

        val strona = dedup.odsiej(listOf(cap(7), cap(7), cap(8)))

        assertEquals(listOf(7L, 8L), strona.map { it.id })
    }

    /** Każdy PagingSource ma własny zbiór — odświeżenie listy nie może zjeść wszystkich wyników. */
    @Test
    fun `nowy deduplikator zaczyna od zera`() {
        PageDeduplicator().odsiej(listOf(cap(1), cap(2)))

        val poOdswiezeniu = PageDeduplicator().odsiej(listOf(cap(1), cap(2)))

        assertEquals(listOf(1L, 2L), poOdswiezeniu.map { it.id })
    }
}

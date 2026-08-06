package pl.sroki.cci.android.data

import pl.sroki.cci.android.model.Cap

/**
 * Odsiewa kapsle, które API oddało już na wcześniejszej stronie.
 *
 * `LazyPagingItems` wymaga unikalnych kluczy, a klucze budujemy z `cap.id` — powtórzony kapsel
 * na styku stron wywalał ekran wyjątkiem `Key … was already used`. Nie jest to teoria: paginacja
 * po zmiennym sortowaniu (najbardziej `LatestCapsPagingSource` — dokładany kapsel przesuwa okno)
 * potrafi oddać ten sam wpis dwa razy.
 *
 * Zbiór żyje tyle, co instancja PagingSource, czyli dokładnie tyle, co jedno przewijanie listy;
 * `invalidate()` tworzy nowy PagingSource i nowy, pusty zbiór.
 */
class PageDeduplicator {
    private val widziane = HashSet<Long>()

    /** @return strona bez kapsli, które już przeszły przez ten deduplikator. */
    fun odsiej(caps: List<Cap>): List<Cap> = caps.filter { widziane.add(it.id) }
}

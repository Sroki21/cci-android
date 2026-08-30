package pl.sroki.cci.android.model.binder

/**
 * Zgodność zapisanego snapshotu kapsla z aktualnym stanem katalogu crowncaps.
 *
 * `raw` to wartość utrwalana w kolumnie `cap_cache.catalog_status` — konwersja odbywa się
 * wyłącznie na granicy warstwy danych, wyżej krąży już typ.
 *
 * Wcześniej te statusy były zwykłymi Stringami rozsianymi po trzech miejscach i jedno się
 * rozjechało: `BindersScreen.isFlagged` nie znało `producer_removed`, więc kapsel z usuniętym
 * producentem liczył się do odznaki i pokazywał w Weryfikacji, ale w Klaserach nie był
 * podświetlany.
 *
 * Zbiór „oflagowanych" jest dlatego definiowany **negatywnie i tylko w jednym miejscu**:
 * `NOT IN ('ok', 'unknown')` w `CapCacheDao.flaggedCountFlow`/`flaggedCapsFlow`. Negatywnie,
 * bo lista pozytywna wymagałaby dopisania każdego nowego statusu — dokładnie tak powstał tamten
 * rozjazd. Warstwa UI nie powtarza tego warunku: `CapDetailScreen` i `CollectionVerificationScreen`
 * rozbierają status **wyczerpującym `when` bez gałęzi `else`**, więc nowy wariant nie przejdzie
 * kompilacji, dopóki ktoś nie zdecyduje, co ma znaczyć na ekranie.
 */
enum class CatalogStatus(val raw: String) {
    /** Jeszcze nieweryfikowany. */
    UNKNOWN("unknown"),

    /** Zgodny z katalogiem. */
    OK("ok"),

    /** Zmieniony w katalogu — decyzja należy do użytkownika. */
    UPDATED("updated"),

    /** Pod tym ID jest inny kapsel. */
    SWAPPED("swapped"),

    /** Zniknął z katalogu. */
    MISSING("missing"),

    /** Ręcznie wybrany producent zniknął z listy producentów kapsla. */
    PRODUCER_REMOVED("producer_removed");

    companion object {
        fun from(raw: String?): CatalogStatus = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

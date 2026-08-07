package pl.sroki.cci.android.model.binder

/** Liczba kapsli posiadanych z danego kraju — agregat z lokalnej kolekcji, niezależny od API. */
data class CountryCapCount(val country: String, val count: Int)

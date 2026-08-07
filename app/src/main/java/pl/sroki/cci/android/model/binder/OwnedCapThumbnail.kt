package pl.sroki.cci.android.model.binder

/** Miniatura posiadanego kapsla z danego kraju — z lokalnego cache, bez zapytania do API. */
data class OwnedCapThumbnail(val capId: Long, val imageUrl: String)

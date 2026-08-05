package pl.sroki.cci.android.model.binder

/**
 * Domenowy widok zapamiętanego stanu kapsla: snapshot z chwili przypięcia, wynik weryfikacji
 * oraz ręczny wybór producenta. Odpowiednik encji `CapCache` bez `lastVerifiedAt`, które jest
 * czysto techniczną kolejką weryfikacji i nikogo poza warstwą danych nie obchodzi.
 */
data class CachedCap(
    val capId: Long,
    val name: String,
    val country: String,
    val imageUrl: String,
    val createdAt: String?,
    val createdById: Int?,
    val updatedAt: String?,
    val catalogStatus: CatalogStatus,
    val selectedProducerId: Int?,
    val producer: String
)

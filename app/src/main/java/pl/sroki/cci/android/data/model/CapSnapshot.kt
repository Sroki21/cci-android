package pl.sroki.cci.android.data.model

/**
 * Niezmienny snapshot identyfikujący kapsel, przechwytywany przy dodawaniu do klasera.
 * Renderowany w klaserach i porównywany przy weryfikacji (fingerprint: createdAt + createdById +
 * updatedAt + imageUrl). Utrwalany w Room (cap_cache) i Firestore (dokument pozycji).
 */
data class CapSnapshot(
    val name: String,
    val country: String,
    val imageUrl: String,
    val createdAt: String?,
    val createdById: Int?,
    val updatedAt: String?
)

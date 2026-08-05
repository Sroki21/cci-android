package pl.sroki.cci.android.model.binder

/**
 * Domenowy widok klasera. Bez adnotacji Room i bez `firestoreId` — identyfikator dokumentu
 * w chmurze jest szczegółem synchronizacji i nie ma czego szukać w warstwie UI.
 */
data class BinderView(
    val id: Long,
    val name: String
)

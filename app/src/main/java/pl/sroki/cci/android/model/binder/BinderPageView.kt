package pl.sroki.cci.android.model.binder

/** Domenowy widok strony klasera — bez adnotacji Room i bez `firestoreId`. */
data class BinderPageView(
    val id: Long,
    val binderId: Long,
    val pageNumber: Int
)

package pl.sroki.cci.android.ui.catalog.caps

import pl.sroki.cci.android.model.Cap

/**
 * Dane wyłącznie dla @Preview. Siedziały obok modelu domenowego w model/Cap.kt jako `caps`,
 * gdzie wyglądały na część modelu — a jedynymi konsumentami są podglądy w tym pakiecie.
 */
val previewCaps = listOf(
    Cap(
        id = 1L,
        description = "Heineken Dark",
        country = "Netherlands",
        product = "Beer",
        purpose = "Bottle closure",
        liner = "Plastic",
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg",
    ),
    Cap(
        id = 2L,
        description = "Heineken Dark Florida",
        country = "Netherlands",
        product = "Beer",
        purpose = "Bottle closure",
        liner = "Plastic",
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/2.c7900789.jpeg",
    )
)

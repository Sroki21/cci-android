package pl.sroki.cci.android.data

import kotlinx.serialization.json.Json

/**
 * Jedna konfiguracja [Json] dla całej aplikacji — konwerter Retrofita i ręczne parsowanie
 * odpowiedzi w repozytoriach.
 *
 * `ignoreUnknownKeys`, bo serwis dokłada do odpowiedzi pola, których modele nie znają, a nowe
 * pole nie może wywracać ekranu. Wspólna instancja zamiast kopii w każdej klasie: dotąd te same
 * dwie linijki stały w trzech miejscach i rozjechanie ich dałoby błąd widoczny dopiero w locie.
 */
internal val AppJson = Json { ignoreUnknownKeys = true }

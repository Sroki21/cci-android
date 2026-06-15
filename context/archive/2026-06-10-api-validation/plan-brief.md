# API Validation — Plan Brief

> Full plan: `context/changes/api-validation/plan.md`
> Research: `context/changes/api-validation/research.md`

## What & Why

Audyt zgodności 7 endpointów REST crowncaps.info z modelami Kotlin. Bez tej
weryfikacji błędy parsowania JSON mogą crashować aplikację lub cicho pomijać
dane — szczególnie groźne przy polu `liner.imageUrl` (brak w modelu) i niezgodnym
`pageSize` w QuickSearch.

## Starting Point

Projekt posiada 3 serwisy Retrofit z 7 endpointami. Konfiguracja `ignoreUnknownKeys = true`
w `NetworkModule` chroni przed nieznanymi polami, ale nie chroni przed brakującymi —
pole deklarowane w modelu jako non-null, nieobecne w JSON, spowoduje crash.

## Desired End State

Wszystkie 7 endpointów potwierdzone HTTP 200; każda anomalia kontrakt vs. kod
naprawiona. `pageSize` w Pager odpowiada faktycznym rozmiarom stron API. Pole
`liner.imageUrl` dostępne dla UI.

## Key Decisions Made

| Decyzja | Wybór | Dlaczego |
|---|---|---|
| `Liner.imageUrl` nullable | `String? = null` | Wsteczna kompatybilność z istniejącymi Preview i testami |
| `pageSize` w QuickSearch | 20 (nie 60) | API zawsze zwraca 20 — niezgodność powodowała nieoptymalne prefetching |
| Brak testów integracyjnych | — | Live API nie jest dostępne w CI; weryfikacja manualna wystarczająca |

## Scope

**In scope:** audyt 7 endpointów + naprawa `Liner.imageUrl` + naprawa `pageSize`.

**Out of scope:** nowe endpointy, testy integracyjne, zmiana architektury.

## Architecture / Approach

Dwie fazy: (1) rekonesans — HTTP + porównanie JSON vs. modele; (2) naprawa
konkretnych anomalii. Obie fazy **zakończone** (szczegóły w `research.md`).

## Phases at a Glance

| Faza | Co dostarcza | Status |
|---|---|---|
| 1. Rekonesans | Weryfikacja HTTP + mapowanie anomalii | ✅ Done |
| 2. Naprawa anomalii | `pageSize = 20`, `Liner.imageUrl` | ✅ Done |

## Open Risks & Assumptions

- Parametr `perPage` jest ignorowany przez endpoint `/api/v1/caps?query=` — przyjmujemy `pageSize = 20` jako stały kontrakt API; może ulec zmianie w przyszłości.
- `seriesSortOrder: Int?` zwraca `0` (nie `null`) gdy kapsel nie ma serii — bezpieczne, ale asymetryczne.
- Pola `brands` i `scripts` w `CapExtended` — zwracane przez API, nieużywane w UI; kandydaci do przyszłego rozszerzenia ekranu szczegółów.

## Success Criteria (Summary)

- Aplikacja kompiluje się bez błędów po naniesieniu poprawek.
- `QuickSearchScreen` paginuje po 20 wyników/stronę.
- `CapDetailScreen` parsuje `liner.imageUrl` bez crash (dostępne dla UI w przyszłości).

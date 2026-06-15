# API Validation — Implementation Plan

## Overview

Weryfikacja poprawności działania wszystkich 7 endpointów REST API serwisu
crowncaps.info oraz zgodności odpowiedzi JSON z modelami Kotlin projektu.
Zmiana nie dodaje funkcjonalności — audyt i naprawa anomalii kontrakt vs. kod.

## Current State Analysis

Bazowy projekt posiada 3 serwisy Retrofit (`CapApiService`, `CountryApiService`,
`CategoryApiService`) z 7 endpointami. Wszystkie wywołania anonimowe. Konfiguracja
`ignoreUnknownKeys = true` w `NetworkModule` chroni przed nieznanymi polami JSON.
Badanie wstępne udokumentowane w `research.md`.

## Desired End State

1. Wszystkie 7 endpointów potwierdzone jako osiągalne (HTTP 200).
2. Modele Kotlin zgodne z kontraktem JSON — każda anomalia naprawiona lub udokumentowana.
3. `pageSize` w konfiguracji Pager odpowiada faktycznym rozmiarom stron zwracanym przez API.
4. Żaden model nie pomija pól zwracanych przez serwer, które mogą być istotne dla UI.

## What We're NOT Doing

- Pisanie testów integracyjnych przeciwko live API.
- Dodawanie nowych endpointów.
- Zmiana architektury warstwy danych.

---

## Phase 1: Rekonesans — weryfikacja HTTP + struktury JSON

### 1.1 Weryfikacja osiągalności endpointów

Ręczne lub skryptowe zapytania do wszystkich 7 endpointów. Dokumentacja statusu HTTP
i przykładowych odpowiedzi JSON.

**Docelowe endpointy:**
- `GET /data/catalog/caps/countries`
- `GET /data/catalog/categories`
- `GET /api/v1/caps/latest?page=1&perPage=20`
- `GET /api/v1/caps?query=test&page=1&perPage=20`
- `GET /api/v1/countries/{id}/caps?page=1&perPage=20`
- `GET /api/v1/categories/caps?category[]=1&page=1&perPage=20`
- `GET /api/v1/caps/{id}`

### 1.2 Porównanie JSON vs. modele Kotlin

Dla każdego endpointu: porównaj pola JSON z deklaracjami modeli Kotlin.
Wykryj: brakujące pola, błędne typy, brakujące `@SerialName`, nullable vs. non-null.

---

## Phase 2: Naprawa anomalii

### 2.1 `QuickSearchPagingSource` — `pageSize` niezgodny z API

**Plik:** `app/src/main/java/pl/sroki/cci/android/ui/catalog/caps/quicksearch/QuickSearchViewModel.kt`

API `GET /api/v1/caps?query=` ignoruje parametr `perPage` i zawsze zwraca 20 wyników.
`PagingConfig(pageSize)` musi odpowiadać faktycznej liczbie elementów per strona.

**Zmiana:** `pageSize = 60` → `pageSize = 20`.

### 2.2 `Liner` — brak pola `imageUrl`

**Plik:** `app/src/main/java/pl/sroki/cci/android/model/Liner.kt`

API `GET /api/v1/caps/{id}` zwraca `liner: { id, name, imageUrl }`. Model nie deklarował
`imageUrl` — parsowanie pomijało pole.

**Zmiana:** dodać `val imageUrl: String? = null` (nullable dla wstecznej kompatybilności).

---

## Success Criteria

### Automated

- Kompilacja: `./gradlew :app:compileDebugKotlin`
- Testy: `./gradlew :app:testDebugUnitTest`

### Manual

- Wszystkie 7 endpointów zwraca HTTP 200.
- `QuickSearchScreen` poprawnie paginuje po 20 wyników/stronę.
- `CapDetailScreen` — endpoint `/api/v1/caps/{id}` deserializowany bez błędów.
- `Page<T>` wrapper — pola `currentPage`, `lastPage`, `perPage`, `total` są poprawnie parsowane.
- Modele `Product`, `Purpose`, `Series` zweryfikowane i zgodne (brak zmian kodu).

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done.

### Phase 1: Rekonesans

- [x] 1.1 Weryfikacja osiągalności 7 endpointów — wszystkie HTTP 200 ✅
- [x] 1.2 Porównanie JSON vs. modele — 2 anomalie wykryte ✅

### Phase 2: Naprawa anomalii

- [x] 2.1 `QuickSearchViewModel` — `pageSize = 20` ✅
- [x] 2.2 `Liner.imageUrl: String? = null` ✅

### Verification

- [x] V.1 Modele `Product`, `Purpose`, `Series` zweryfikowane — czyste, bez zmian ✅
- [x] V.2 `Page<T>` — `@SerialName` dla snake_case potwierdzone jako poprawne ✅
- [ ] V.3 Kompilacja bez błędów (weryfikacja po wdrożeniu zmian)
- [ ] V.4 Testy jednostkowe przechodzą

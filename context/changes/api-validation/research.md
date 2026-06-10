---
date: 2026-06-10T16:42:00+02:00
researcher: Claude Sonnet 4.6
git_commit: n/a (brak repozytorium git)
branch: n/a
repository: cci-android-master
topic: "Walidacja API crowncaps.info — poprawność działania i zgodność z modelami Kotlin"
tags: [research, api, crowncaps, retrofit, kotlinx-serialization, paging]
status: complete
last_updated: 2026-06-10
last_updated_by: Claude Sonnet 4.6
last_updated_note: "Dodano follow-up: weryfikacja modeli Product, Purpose, Series + statusy napraw anomalii"
---

# Research: Walidacja API crowncaps.info

**Data**: 2026-06-10  
**Researcher**: Claude Sonnet 4.6  
**Git Commit**: n/a (brak repozytorium git)  
**Repository**: cci-android-master  

---

## Research Question

Czy API serwisu crowncaps.info działa poprawnie? Weryfikacja wszystkich 7
endpointów: osiągalność (HTTP status), struktura odpowiedzi JSON oraz
zgodność pól z modelami Kotlin używanymi przez aplikację Android.

---

## Summary

Wszystkie 7 endpointów zwraca **HTTP 200 OK**. Serwer działa. Modele Kotlin
są poprawne — `ignoreUnknownKeys = true` w `NetworkModule.kt` skutecznie
chroni przed dodatkowymi polami w JSON. Wykryto i naprawiono **dwie anomalie**:
endpoint wyszukiwania ignorował parametr `perPage` (naprawiono `QuickSearchViewModel`),
oraz `Liner` nie deklarował pola `imageUrl` zwracanego przez API (naprawiono model).
Modele `Product`, `Purpose` i `Series` zweryfikowano — są czyste.

> **Uwaga metodyczna:** jeden z agentów badawczych błędnie zgłosił brakującą
> adnotację `@SerialName("per_page")` w modelu `Page.kt`. Weryfikacja
> bezpośrednia kodu źródłowego potwierdziła, że adnotacja **jest obecna**
> (`Page.kt:12`). Wynik agenta został odrzucony.

---

## Detailed Findings

### Endpoint 1 — `GET /data/catalog/caps/countries`

- **Status:** 200 OK
- **Odpowiedź:** tablica `[]`, 229 krajów
- **Przykład:**
  ```json
  { "id": 302, "name": "-Multiple countries", "imageUrl": "https://ddxwnzii69fzh.cloudfront.net/flags/MI.png" }
  ```
- **Model Kotlin** (`data/model/Country.kt`): `id: Long, name: String, imageUrl: String`
- **Zgodność:** pełna — nazwy pól identyczne, typy kompatybilne.

---

### Endpoint 2 — `GET /data/catalog/categories`

- **Status:** 200 OK
- **Odpowiedź:** tablica `[]`, 88 kategorii
- **Przykład:**
  ```json
  { "id": 8, "name": "Animals" }
  ```
- **Model Kotlin** (`model/Category.kt`): `id: Int, name: String`
- **Zgodność:** pełna.

---

### Endpoint 3 — `GET /api/v1/caps/latest?page=1&perPage=N`

- **Status:** 200 OK
- **Wrapper `Page<Cap>`** zwraca klucze: `current_page`, `data`, `last_page`,
  `per_page`, `total` + 8 dodatkowych (`links`, `from`, `to`, url-e stron)
- **Przykład Cap w `data[]`:**
  ```json
  { "id": 366440, "country": "-Unknown", "product": "Beer",
    "purpose": "Bottle closure", "liner": "Plastic",
    "isInCollection": false, "imageUrl": "https://…/thumbnails/…" }
  ```
- **Model `Page<T>`** (`model/Page.kt`): adnotacje `@SerialName` poprawne
  dla wszystkich pól snake_case (`last_page`, `current_page`, `per_page`).
- **Model `Cap`** (`model/Cap.kt`): pola `imageUrl` i `isInCollection`
  używają camelCase — zgodne z JSON.
- **Pole `description`:** nullable z defaultem `""` — poprawnie obsługuje
  brak pola w JSON.
- **Zgodność:** pełna. Dodatkowe pola JSON ignorowane przez `ignoreUnknownKeys = true`.

---

### Endpoint 4 — `GET /api/v1/caps?query=heineken&page=1&perPage=N`

- **Status:** 200 OK
- **Struktura:** identyczna jak Endpoint 3.
- **Anomalia:** parametr `perPage` jest **ignorowany** — API zawsze zwraca
  20 wyników niezależnie od przekazanej wartości. `per_page` w JSON = 20.
- **Wpływ na aplikację:** `QuickSearchViewModel` używa `PagingConfig(pageSize = 60)`,
  ale faktycznie otrzymuje 20 elementów. Paginacja działa, lecz `pageSize`
  w konfiguracji nie odpowiada rzeczywistości — może powodować
  nieoptymalne prefetching.

---

### Endpoint 5 — `GET /api/v1/countries/{id}/caps?page=1&perPage=N`

- **Status:** 200 OK
- **Struktura:** identyczna jak Endpoint 3. Parametr `perPage` respektowany.
- **Zgodność:** pełna.

---

### Endpoint 6 — `GET /api/v1/categories/caps?category[]=N&page=1&perPage=N`

- **Status:** 200 OK
- **Struktura:** identyczna jak Endpoint 3. Parametr `perPage` respektowany.
- **Zgodność:** pełna.

---

### Endpoint 7 — `GET /api/v1/caps/{id}` (szczegóły kapsla)

- **Status:** 200 OK (testowano id: 1, 2, 100)
- **JSON zwraca 35 kluczy** pierwszego poziomu; model `CapExtended` ma 24 pola.
- **Wszystkie pola wymagane są obecne** i zgodne:

  | Pole Kotlin | Klucz JSON | Typ JSON | Status |
  |---|---|---|---|
  | `id: Int` | `id` | Int | OK |
  | `country: Country` | `country` | Obiekt | OK — patrz uwaga |
  | `product: Product` | `product` | `{ id, name }` | OK |
  | `purpose: Purpose` | `purpose` | `{ id, name }` | OK |
  | `liner: Liner` | `liner` | Obiekt | OK — patrz uwaga |
  | `imageUrl: String` | `imageUrl` | String (CDN URL) | OK |
  | `usersCount: Int` | `usersCount` | Int | OK |
  | `createdAt: Instant` | `createdAt` | `"2011-04-30T15:20:54+04:00"` | OK — ISO 8601 z offsetem |

- **Pola nullable działają poprawnie:** `series: Series?` i `periodUsed: PeriodUsed?`
  zwracają JSON `null` — deserializacja poprawna.
- **`seriesSortOrder`:** deklaracja `Int?`, ale API nigdy nie zwraca `null`
  — zwraca `0` gdy brak serii. Bezpieczne, ale warto wiedzieć.

---

## Anomalie kontrakt vs. kod — modele pomocnicze

### `country` obiekt w `CapExtended`

JSON zwraca `{ id, name, imageUrl }`, ale model `Country`
(`data/model/Country.kt`) **deklaruje `imageUrl`** — zgodne ✅.

Natomiast ten sam model `Country` jest używany w `CountriesRepository`
(endpoint `/data/catalog/caps/countries`) gdzie JSON też zwraca `imageUrl`.
Spójność modelu zachowana.

### `liner` obiekt w `CapExtended` — ✅ NAPRAWIONO

JSON zwraca `{ id, name, imageUrl: "https://crowncaps.info/images/liner-plastic.png" }`.
Model `Liner` (`model/Liner.kt`) nie deklarował pola `imageUrl` — naprawiono
przez dodanie `val imageUrl: String? = null`. Wartość domyślna `null` zachowuje
kompatybilność z istniejącymi Preview i testami.

### `product`, `purpose`, `series` — ✅ Czyste (zweryfikowano 2026-06-10)

Weryfikacja żywym API na 4 różnych kapsłach (id: 1, 24377, 50000, 100000):

| Model | Pola JSON | Pola Kotlin | Status |
|---|---|---|---|
| `Product` | `{ id, name }` | `id: Int, name: String` | ✅ Pełna zgodność |
| `Purpose` | `{ id, name }` | `id: Int, name: String` | ✅ Pełna zgodność |
| `Series` | `{ id, name, info, total, year }` | `id, name, info?, total, year?` | ✅ Pełna zgodność |

Przykład `Series` z rzeczywistego API (cap 24377):
```json
{ "id": 155, "name": "The Simpsons",
  "info": "Promotional set based on the US cartoon series \"The Simpsons\"",
  "total": 5, "year": 1998 }
```
Żaden z trzech modeli nie ma dodatkowych pól w JSON ani brakujących pól wymaganych.

---

## Code References

- `app/src/main/java/ru/sroki/cci/android/model/Page.kt:7-16` — model paginacji z poprawnymi `@SerialName`
- `app/src/main/java/ru/sroki/cci/android/model/Cap.kt:8-21` — model listy
- `app/src/main/java/ru/sroki/cci/android/model/CapExtended.kt:11-38` — model szczegółów (23 pola)
- `app/src/main/java/ru/sroki/cci/android/data/model/Country.kt:5-9` — model kraju
- `app/src/main/java/ru/sroki/cci/android/di/NetworkModule.kt:33` — `ignoreUnknownKeys = true`
- `app/src/main/java/ru/sroki/cci/android/data/datasource/remote/CapApiService.kt:27-31` — endpoint wyszukiwania (perPage ignorowany przez API)
- `app/src/main/java/ru/sroki/cci/android/ui/catalog/caps/quicksearch/QuickSearchViewModel.kt:21` — `PagingConfig(pageSize = 20)` po naprawie (było 60)
- `app/src/main/java/ru/sroki/cci/android/model/Liner.kt:6` — `imageUrl: String? = null` po naprawie
- `app/src/main/java/ru/sroki/cci/android/model/Product.kt:6` — `{ id, name }` — zgodne, bez zmian
- `app/src/main/java/ru/sroki/cci/android/model/Purpose.kt:6` — `{ id, name }` — zgodne, bez zmian
- `app/src/main/java/ru/sroki/cci/android/model/Series.kt:6-12` — `{ id, name, info?, total, year? }` — zgodne, bez zmian

---

## Architecture Insights

1. **`ignoreUnknownKeys = true` jest kluczową decyzją** (`NetworkModule.kt:33`).
   API zwraca znacznie więcej danych niż model konsumuje (35 pól vs. 24 w
   `CapExtended`). Bez tej opcji każda nowa wersja API mogłaby crashować
   aplikację. Decyzja słuszna.

2. **API używa konwencji camelCase** dla nazw pól (np. `imageUrl`,
   `isInCollection`, `usersCount`) — wyjątkiem jest wrapper paginacji, który
   używa snake_case (`last_page`, `current_page`, `per_page`). Stąd potrzeba
   `@SerialName` tylko w `Page.kt` — pozostałe modele nie potrzebują adnotacji.

3. **`Page<T>` zwraca dodatkowe pola paginacyjne** (8 pól: `links`, `from`,
   `to`, `first_page_url`, `last_page_url`, `next_page_url`, `prev_page_url`,
   `path`) — są ignorowane. Aplikacja używa tylko `currentPage`/`lastPage`
   do wyznaczenia `nextKey` w PagingSource — logika poprawna.

---

## Open Questions

1. ~~**`perPage` ignorowany w quicksearch**~~ — **NAPRAWIONO** (`QuickSearchViewModel.kt:21`, `pageSize = 20`).

2. ~~**`liner.imageUrl` nieużywane**~~ — **NAPRAWIONO** (`Liner.kt:6`, dodano `imageUrl: String? = null`). Pole jest teraz dostępne dla UI, ale `CapDetailView` jeszcze go nie renderuje — kandydat do rozszerzenia ekranu szczegółów.

3. **`brands` i `scripts` w `CapExtended`:** API zwraca listy marek i
   pism/alfabetów — czy są planowane do wyświetlenia w szczegółach kapsla?

4. **`range` obiekt:** API zwraca obiekt `range` z polami `startYearCirca`,
   `endYearCirca`, `description`, `isoRange` — potencjalne wzbogacenie
   ekranu szczegółów (obok pola `year` i `periodUsed`).

---

## Status endpointów — tabela zbiorcza

| # | Endpoint | HTTP | Zgodność modelu | Uwagi |
|---|---|---|---|---|
| 1 | `GET /data/catalog/caps/countries` | ✅ 200 | ✅ Pełna | — |
| 2 | `GET /data/catalog/categories` | ✅ 200 | ✅ Pełna | — |
| 3 | `GET /api/v1/caps/latest` | ✅ 200 | ✅ Pełna | — |
| 4 | `GET /api/v1/caps?query=` | ✅ 200 | ✅ Pełna | ✅ `pageSize = 20` naprawiony |
| 5 | `GET /api/v1/countries/{id}/caps` | ✅ 200 | ✅ Pełna | — |
| 6 | `GET /api/v1/categories/caps` | ✅ 200 | ✅ Pełna | — |
| 7 | `GET /api/v1/caps/{id}` | ✅ 200 | ✅ Pełna | ✅ `liner.imageUrl` naprawiony |

# Advanced Search — Plan Brief

> Full plan: `context/changes/advanced-search/plan.md`

## What & Why

Odblokowanie przycisku „Szukanie zaawansowane" na HomeScreen i dostarczenie formularza
4-filtrowego (ID, Tekst, Kraj, Producent) z paginowanymi wynikami poniżej. Zapobiega
ślepym zapytaniom tekstowym — kolekcjoner może szukać po kraju lub producencie bez
wpisywania słów kluczowych.

## Starting Point

- `CapApiService.getByQuery()` + `getById()` istnieją; brak `advancedSearch()`.
- `QuickSearchViewModel` + `QuickSearchPagingSource` — wzorzec gotowy do skopiowania.
- `HomeScreen` — przycisk „Szukanie zaawansowane" z `enabled = false`.
- `Screen.AdvancedSearch` i `CountriesRepository` istnieją.

## Desired End State

Tap „Szukanie zaawansowane" → `AdvancedSearchScreen` z formularzem na górze i
`LazyColumn` wyników poniżej. Kraj wybierany z dialogu (lista 229 krajów ładowana
raz przy wejściu). Licznik „Znaleziono: N kapsli" po pierwszej stronie wyników.
Checkbox „Tylko kapsle w kolekcji" widoczny wyłącznie dla zalogowanego.

## Key Decisions Made

| Decyzja | Wybór | Dlaczego |
|---|---|---|
| Zapytanie po ID | `getById()` zamiast `advancedSearch()` | Szybsze, precyzyjne — API zwraca jeden wynik |
| Parametry serwera | Best-effort (`country_id`, `producer`) | Nieznana obsługa API; `ignoreUnknownKeys = true` chroni przed błędem |
| Wzorzec Pager | Taki sam jak `QuickSearchViewModel` | Spójność — `_filterTrigger + flatMapLatest` już przetestowane |
| Kraj picker | `AlertDialog` z `LazyColumn` + wyszukiwanie | Brak gotowego autocomplete w Compose Material 3; dialog jest pewny |

## Scope

**In scope:** `AdvancedSearch.kt` (model filtrów), `CapApiService.advancedSearch()`,
`AdvancedSearchPagingSource`, `AdvancedSearchViewModel`, `AdvancedSearchScreen`
(FilterForm + CapResultsList), `Screen.AdvancedSearch` + route, odblokowanie HomeScreen.

**Out of scope:** operatorowy filtering po stronie serwera, kategorie jako filtr,
sortowanie, zachowanie stanu filtrów po powrocie.

## Architecture / Approach

Dwie fazy: (1) warstwa danych — model filtrów + endpoint + PagingSource + metoda w
`CapsRepository`; (2) warstwa UI — ViewModel + Screen + nawigacja. Render wyników
reużywa `CapListItem` z QuickSearch; totalResults przekazywany przez callback z
PagingSource do ViewModelu przez `onTotalLoaded()`.

## Phases at a Glance

| Faza | Co dostarcza | Główne ryzyko |
|---|---|---|
| 1. Data layer | Model filtrów + endpoint + PagingSource + repo method | `country_id`/`producer` ignorowane przez API |
| 2. ViewModel + Screen + Nav | Pełny ekran z formularzem i wynikami | Wydajność dialogu krajów (229 pozycji) |

**Prerequisites:** brak — buduje na istniejącym `CapsRepository` i `CountriesRepository`.

## Open Risks & Assumptions

- Parametry `country_id`, `producer`, `in_collection` są optymistyczne — API może je ignorować. Brak wpływu na poprawność (server po prostu odfiltruje wszystkie kapsle zamiast podzbioru).
- `seriesSortOrder = 0` gdy brak serii — znane zachowanie z badań API, bez wpływu na ten ekran.

## Success Criteria (Summary)

- Tap „Szukanie zaawansowane" → otwiera ekran z formularzem i pustą listą.
- Przycisk „Szukaj" nieaktywny przy pustych filtrach; aktywny po wypełnieniu co najmniej jednego.
- Wpisanie tekstu + Szukaj → paginowane wyniki; tap na kapsel → `CapDetailScreen`.
- Wpisanie poprawnego ID + operator „Równe" + Szukaj → jeden wynik.
- Checkbox kolekcji widoczny tylko dla zalogowanego.
- `./gradlew :app:compileDebugKotlin` bez błędów.

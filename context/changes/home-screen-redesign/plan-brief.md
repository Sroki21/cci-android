# Home Screen Redesign — Plan Brief

> Full plan: `context/changes/home-screen-redesign/plan.md`

## What & Why

Ekran główny przechodzi z layoutu „lista zakładek w TopAppBar z togglem wyszukiwania" na layout z trwałym polem Szukaj na górze i czterema przyciskami nawigacyjnymi. TopAppBar zyskuje stan sesji: przycisk „Zaloguj" gdy użytkownik nie jest zalogowany, email po zalogowaniu.

## Starting Point

`HomeScreen.kt` ma TopAppBar z tytułem + ikoną wyszukiwania (toggle ukrywający tytuł), i 4 `NavigationItem`: Picture search, Additions, Countries, Klasery. `SessionRepository` trzyma `isLoggedIn: StateFlow<Boolean>` ale nie przechowuje emaila. Brak ViewModelu dla HomeScreen.

## Desired End State

HomeScreen wyświetla `OutlinedTextField` „Szukaj" na górze treści; poniżej: Szukaj wg zdjęcia (aktywny → PictureSearch), Szukanie zaawansowane (disabled), Statystyki (disabled), Klasery (aktywny). TopAppBar pokazuje email zalogowanego użytkownika lub `TextButton("Zaloguj")`.

## Key Decisions Made

| Decyzja | Wybór | Uzasadnienie | Źródło |
|---------|-------|--------------|--------|
| Wyzwalanie szukania | Submit (Enter / ikona) | Jeden request, brak debounce | Plan |
| Placeholdery S-04 / Statystyki | Widoczne, disabled | Kompletny UX od razu widoczny | Plan |
| Przechowywanie username | Email z LoginRequest | Zero nowych requestów API | Plan |
| ViewModel dla HomeScreen | Nowy HomeViewModel | Zgodnie z wzorcem projektu; SessionRepository nie wchodzi do UI bezpośrednio | Plan |
| Język UI | Polski | Spójność z istniejącym „Klasery" | Plan |

## Scope

**In scope:**
- Redesign `HomeScreen.kt` (layout, TopAppBar, 4 przyciski)
- Rozszerzenie `SessionRepository` o `userName`
- Zapis emaila w `AuthRepository.login()`
- Nowy `HomeViewModel`
- Rozszerzenie `NavigationItem` o parametr `enabled`
- Wiring w `MainActivity.kt` (`onLoginClick`)

**Out of scope:**
- Ekran Szukanie zaawansowane (S-04)
- Ekran Statystyki (osobna zmiana)
- Wylogowanie z TopAppBar
- Fix startDestination → Login przy starcie
- Usunięcie kodu Countries/Latest z NavGraph

## Architecture / Approach

`SessionRepository` (Singleton) → `HomeViewModel` (HiltViewModel, combine isLoggedIn + userName) → `HomeScreen` (collectAsState). Nawigacja przez callbacki (`onClick: (Screen) → Unit`, `onSearch`, `onLoginClick`) przekazywane z `MainActivity`. `NavigationItem` dostaje `enabled: Boolean = true` i stosuje `Modifier.clickable(enabled)` + alpha.

## Phases at a Glance

| Faza | Co dostarcza | Główne ryzyko |
|------|-------------|---------------|
| 1. Home Screen — pełny redesign | Działający nowy layout z auth state | `combine` dwóch StateFlow w HomeViewModel — sprawdzić czy emisja przy zimnym starcie działa poprawnie |

**Prerequisites:** Brak — wszystkie zależności (SessionRepository, AuthRepository, NavGraph) już istnieją.  
**Estimated effort:** ~1 sesja, 6 plików.

## Open Risks & Assumptions

- `FilterList` i `BarChart` z `material-icons-extended` — już w zależności projektu, powinny być dostępne przez wildcard import.
- Email jako identyfikator użytkownika w TopAppBar — akceptowalne na tym etapie; pełna nazwa profilu wymaga `GET /data/users/current` (S-0x).
- `ContentAlpha.disabled` — sprawdzić dostępność w Material3 (`LocalContentAlpha` vs `disabledContentColor`).

## Success Criteria (Summary)

- Pole Szukaj wyzwala nawigację do QuickSearch po zatwierdzeniu
- Szukaj wg zdjęcia i Klasery działają; pozostałe dwa przyciski są wizualnie disabled
- TopAppBar pokazuje poprawny stan (Zaloguj / email) po zalogowaniu i przed

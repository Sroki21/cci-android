# Binder Management — Plan Brief

> Full plan: `context/changes/binder-management/plan.md`

## What & Why

Nowy ekran `BindersScreen` umożliwia tworzenie klaserów, dodawanie stron (limit 15) i usuwanie
przez dialog potwierdzenia. Ekran stanowi fundament pod S-03 (przypisywanie kapsli do pozycji).

## Starting Point

F-02 i F-03 dostarczyły `BinderRepository` i `BinderPageRepository` z pełną logiką CRUD i Firestore
write-through. Brak jakiegokolwiek UI — żadnej trasy, ViewModelu ani ekranu.

## Desired End State

Użytkownik trafia do "Klasery" z HomeScreen, widzi listę klaserów, tworzy nowe przez FAB+dialog,
rozwija klaser żeby zobaczyć/dodać/usunąć strony. Błędy (zajęty klaser, limit 15 stron)
sygnalizowane przez Snackbar.

## Key Decisions Made

| Decision | Wybór | Dlaczego | Źródło |
|---|---|---|---|
| Wejście z HomeScreen | Nowy NavigationItem | Spójne z istniejącym wzorcem HomeScreen | Plan |
| Architektura ekranów | Jeden ekran + inline expand | Minimalne S-02 bez dodatkowych tras | Plan |
| Tworzenie klasera | FAB + AlertDialog | Material 3 standard, 1 pole tekstowe | Plan |
| Wyświetlanie błędów | Snackbar | Nieinwazyjne, Material 3 | Plan |
| Usuwanie | AlertDialog potwierdzenia | Bezpieczne — brak przypadkowych usunięć | User |
| Walidacja nazwy | Wolny tekst, tylko niepuste | Prostota UX | User |
| Testy | ViewModel JVM | Repozytoria pokryte w F-02/F-03 | User |

## Scope

**In scope:**
- Trasa `Screen.Binders`, composable w NavHost, entry w HomeScreen
- `BindersViewModel` ze StateFlow + Channel events (Snackbar)
- `BindersScreen` — LazyColumn z expandable rows, FAB, 3 dialogi (create, delete binder, delete page)
- `BindersViewModelTest` — 5 testów JVM

**Out of scope:**
- Edycja nazwy klasera
- Bottom Navigation Bar
- Compose UI tests
- Walidacja formatu nazwy "Kontynent + numer"

## Architecture / Approach

ViewModel eksponuje `StateFlow<BindersUiState>` (binders, expandedIds, pages per-binder,
dialog flags) + `Flow<BindersEvent>` przez Channel dla Snackbar. Strony subskrybowane
leniwie: pierwszy `toggleExpand(id)` startuje coroutine zbierający `getByBinder(id)`.
Wyjątki z repozytoriów (IllegalStateException) przechwytywane → ShowSnackbar event.

## Phases at a Glance

| Phase | Co dostarcza | Główne ryzyko |
|---|---|---|
| 1. Nawigacja + ViewModel | Działająca nawigacja + pełna logika VM (stub screen) | Hilt DI dla nowego ViewModel |
| 2. Pełne UI + testy | Gotowy ekran + 5 testów JVM | Subskrypcja Flow per-binder w VM |

**Prerequisites:** F-02 (✓), F-03 (✓)
**Estimated effort:** ~2 sesje, 2 fazy

## Open Risks & Assumptions

- Strony subskrybowane leniwie — przy dużej liczbie klaserów jednoczesne rozwinięcie wszystkich uruchomi wiele coroutine naraz. Dla prywatnej kolekcji (dziesiątki klaserów) akceptowalne.
- `ExpandableBinderRow` to nowy komponent współdzielony; może trafić do `ui/components/` w S-03 jeśli będzie reużywany.

## Success Criteria (Summary)

- HomeScreen → "Klasery" → lista → FAB → utwórz → rozwiń → dodaj stronę → usuń — działa end-to-end
- Snackbar przy próbie usunięcia zajętego klasera i przy 16. stronie
- 5 testów JVM zielonych

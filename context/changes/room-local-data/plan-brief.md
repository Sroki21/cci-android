# Room Local Data Layer — Plan Brief

> Full plan: `context/changes/room-local-data/plan.md`

## What & Why

Dodajemy lokalną bazę Room z 4 tabelami niezbędnymi do działania S-01 i S-02.
Bez tej warstwy nie ma gdzie zapisać listy kapsli oczekujących na skatalogowanie
ani struktury klaserów — oba słupki funkcjonalne F-02 odblokowane po tym commicie.

## Starting Point

Projekt nie ma żadnego Room. Dane płyną wyłącznie z Retrofit API.
KSP jest już skonfigurowane (Hilt), `di/NetworkModule.kt` daje gotowy wzorzec modułu Hilt do powielenia.

## Desired End State

Po wdrożeniu: `CciDatabase` z tabelami `pending_cap`, `binder`, `binder_page`, `cap_position`
dostępny przez Hilt w całej aplikacji. Repozytoria z walidacją (max 15 stron, pozycja 1–35,
ochrona zajętego klasera) gotowe do wstrzyknięcia w ViewModele S-01/S-02/S-03/S-04.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| PendingCap fields | tylko `cap_id: Long` | brak duplikacji danych API, minimalne ryzyko rozbieżności | Plan |
| Binder fields | `id + name (String, wolna forma)` | najprostszy schemat, sortowanie po kontynencie w UI | Plan |
| CapPosition fields | `binder_page_id + position + cap_id` | lean schema, metadane kapsla z API na żądanie | Plan |
| Walidacja limitów | w warstwie aplikacji (Repository) | Room nie obsługuje `CHECK` natively, błąd łatwiejszy do obsługi w UI | Plan |
| Migracje | `fallbackToDestructiveMigration()` v1 | brak prod danych do chronienia przed pierwszym release | Plan |
| Testy | in-memory Room (androidTest) | jedyna metoda weryfikująca rzeczywiste SQL i unique constraints | Plan |
| Fazy | 1 faza — wszystko naraz | prosty, niewielki zakres — niepotrzebny podział | Plan |

## Scope

**In scope:**
- Room dependencies (build.gradle)
- 4 encje: `PendingCap`, `Binder`, `BinderPage`, `CapPosition`
- 4 DAO-y z suspend + Flow
- `CciDatabase` + `DatabaseModule` (Hilt)
- 4 repozytoria z walidacją domenową
- Testy in-memory (androidTest): DAO CRUD, unique constraints, limity, kaskady

**Out of scope:**
- Jakiekolwiek UI korzystające z tych repozytoriów (S-01, S-02, S-03, S-04)
- Room migrations z SQL (zastępowane przez destructive fallback w v1)
- Cachowanie metadanych kapsla (name, imageUrl) lokalnie

## Architecture / Approach

```
di/DatabaseModule.kt
  └── CciDatabase (Room, file: "cci.db", fallbackToDestructiveMigration)
       ├── PendingCapDao  →  PendingCapRepository
       ├── BinderDao      →  BinderRepository (+ CapPositionDao dla ochrony przy delete)
       ├── BinderPageDao  →  BinderPageRepository
       └── CapPositionDao →  CapPositionRepository
```

Encje w `data/datasource/local/entity/`, DAO-y w `data/datasource/local/dao/`,
repozytoria bezpośrednio w `data/` — analogicznie do istniejącej struktury Retrofit.

FK z CASCADE: Binder→BinderPage→CapPosition (usunięcie klasera kaskadowo usuwa wszystko).
Unique DB constraint: `(binder_page_id, position)` — duplikat slotu → `SQLiteConstraintException`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Room local data layer | Kompletna warstwa lokalna: encje + DAO + repozytoria + testy | Schemat musi być poprawny od razu — każda zmiana kasuje dane dev |

**Prerequisites:** Brak (F-02 nie ma zależności upstream)
**Estimated effort:** ~1 sesja

## Open Risks & Assumptions

- Room 2.7.0 kompatybilne z compileSdk 37 + KSP — przyjęte, do weryfikacji przy `kspDebugKotlin`
- Testy in-memory wymagają emulatora / podłączonego urządzenia — `connectedDebugAndroidTest` blokujące bez device

## Success Criteria (Summary)

- `./gradlew :app:kspDebugKotlin` przechodzi bez błędów (Hilt + Room codegen)
- `./gradlew :app:connectedDebugAndroidTest` — wszystkie testy in-memory zielone
- Aplikacja startuje bez crashu, `CciDatabase` widoczny w App Inspection z 4 tabelami

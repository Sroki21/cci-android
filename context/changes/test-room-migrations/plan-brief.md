# test-room-migrations — Plan Brief

> Full plan: `context/changes/test-room-migrations/plan.md`

## What & Why

Dodajemy testy instrumentowane dla migracji Room 2→3, 3→4, 4→5, 5→6 oraz łańcuch 3→7 (Phase B
z `context/foundation/test-plan.md`). Ryzyko R2 (HIGH/HIGH): trzy migracje środkowe (3→4, 4→5, 5→6)
nie mają żadnych testów; `MIGRATION_3_4` przebudowuje tabelę i migruje dane — regression tutaj jest
cicha i niszcząca dane użytkownika.

## Starting Point

Baza Room v7 z 6 migracjami w `CciDatabase.kt`. Istniejący `MigrationTest.kt` pokrywa tylko 1→2
i 6→7 (prawdopodobnie niesprawdzone z powodu `exportSchema = false`, który blokuje
`MigrationTestHelper.createDatabase(version)`). Dependency `room-testing:2.7.0` dostępna.

## Desired End State

`MigrationTest.kt` z 7 testami (5 nowych + 2 zweryfikowane), wszystkie przechodzące na
urządzeniu. Katalog `app/schemas/` zawiera 7 plików JSON dla Room 2.7. Każda migracja 1→7 ma
co najmniej jeden test; `migrate3to4` weryfikuje integralność danych (country migruje z
cap\_position do cap\_cache).

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| exportSchema | true (+ schema JSONs) | MigrationTestHelper wymaga JSON do createDatabase(version) | Plan |
| 5→6 co testuje | country_flag table creation | MIGRATION_5_6 tworzy country_flag, nie snapshot columns (błąd w test-planie) | Plan |
| 3→4 głębokość | Pełna integralność danych | INSERT OR IGNORE może cicho gubić dane — tylko test danych to wykryje | Plan |
| 2→3 scope | Dodaj (poza test-planem) | 5-liniowy test eliminuje ostatnią lukę pokrycia | Plan |
| FullChain start | 3→7 (per test-plan) | Pokrywa cały niesprawdzony gap; 1→2 i 6→7 mają osobne testy | Plan |
| CI | Bez zmian | Test-plan: emulator w CI to Phase C lub osobna zmiana | Plan |

## Scope

**In scope:**
- `exportSchema = true` + KSP arg + androidTest assets konfiguracja
- Generowanie schema JSON 1–7 (v7 auto, v1–v6 przez tymczasowe buildy)
- 5 nowych testów: migrate2to3, migrate3to4, migrate4to5, migrate5to6, migrateFullChain3to7
- Weryfikacja że istniejące testy 1→2 i 6→7 nie są broken

**Out of scope:**
- CI/emulator setup
- Modyfikacja logiki migracji
- Testy JVM (Room wymaga prawdziwego SQLite)
- Testy DAO (osobny plik, osobny zakres)

## Architecture / Approach

Infrastruktura (`exportSchema = true` + schema JSONy) jako prereq dla testów. Wszystkie 7 testów
w istniejącym `MigrationTest.kt`; wzorzec: `helper.createDatabase(version)` → raw SQL insert →
`helper.runMigrationsAndValidate(targetVersion, true, MIGRATION_X_Y)` → cursor assertions.
FK chain w testach 3→4 wymaga wstawiania binder → binder_page → cap_position po kolei.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. exportSchema + Schemas | 7 plików JSON w app/schemas/; istniejące testy zweryfikowane | Generowanie v1–v6 wymaga 6 tymczasowych buildów z zmodyfikowanymi entity classes |
| 2. Nowe testy | 5 nowych testów w MigrationTest.kt; wszystkie 7 zielone | migrate3to4 wymaga poprawnego FK setup (binder→page→position) |

**Prerequisites:** urządzenie lub emulator Android do uruchomienia connectedAndroidTest
**Estimated effort:** ~1 sesja (Phase 1 dominuje — generowanie schematów jest jednorazowe)

## Open Risks & Assumptions

- Tymczasowa modyfikacja entity classes dla v1–v6 może wprowadzić błędy jeśli pola zostaną
  niepoprawnie odwzorowane — Room wykryje to przez hash mismatch przy uruchomieniu testów
- Istniejące testy 1→2 i 6→7 mogą być broken — Phase 1 Manual Verification to wykryje przed Phase 2

## Success Criteria (Summary)

- `./gradlew connectedDebugAndroidTest` → 7 testów MigrationTest PASS na urządzeniu
- `migrate3to4` weryfikuje że country='Poland' trafia do cap\_cache i country='' nie trafia
- Katalog `app/schemas/` z 7 plikami JSON commitowany do repo

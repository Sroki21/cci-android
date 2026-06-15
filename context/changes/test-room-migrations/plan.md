---
change_id: test-room-migrations
created: 2026-06-15
updated: 2026-06-15
status: planned
---

# test-room-migrations — Plan implementacji

## Overview

Dodajemy testy instrumentowane (Phase B z `context/foundation/test-plan.md`) pokrywające
migracje Room 2→3, 3→4, 4→5, 5→6 oraz łańcuch 3→7. Poprzedza je jednorazowy setup:
włączenie `exportSchema = true` i wygenerowanie plików JSON dla wszystkich 7 wersji schematu,
których wymaga `MigrationTestHelper.createDatabase(version)`.

## Current State Analysis

- `CciDatabase.kt:23` — `exportSchema = false` blokuje `MigrationTestHelper.createDatabase(version)`,
  bo helper czyta schemat startowy z pliku `schemas/<pkg>/<version>.json`
- `MigrationTest.kt` — 2 istniejące testy: `migrate1To2` i `migrate6To7`; oba prawdopodobnie
  niesprawdzone (CI nie uruchamia testów instrumentowanych), mogą być broken bez schema JSONów
- Brakujące testy: 2→3, 3→4, 4→5, 5→6
- Brak testu łańcuchowego pokrywającego cały gap (3→7)
- `room-testing:2.7.0` dostępny w `androidTestImplementation` (`app/build.gradle:136`)
- Wzorzec istniejących testów: `createDatabase(v)` → insert → `runMigrationsAndValidate(v+N, true, MIGRATION_X_Y)` → cursor assertions

## Desired End State

Po zakończeniu planu:
- `exportSchema = true` w CciDatabase; `app/schemas/…/` zawiera pliki `1.json`–`7.json`
- `MigrationTest.kt` ma 7 testów: 2 istniejące (zweryfikowane) + 5 nowych
- Wszystkie 7 testów przechodzi na fizycznym urządzeniu/emulatorze
- `migrate3to4` weryfikuje integralność danych (migracja `country` z `cap_position` → `cap_cache`)
- `migrateFullChain3to7` potwierdza że dane przeżywają 4 migracje naraz

### Key Discoveries

- `MIGRATION_3_4` to jedyna złożona migracja: przebudowuje `cap_position` (CREATE new + INSERT SELECT + DROP + RENAME) i migruje dane kraju; wymaga wstawienia `binder → binder_page → cap_position` przed testem (FK chain)
- `INSERT OR IGNORE INTO cap_cache SELECT ... WHERE country != ''` — wiersze z pustym krajem NIE migrują do `cap_cache`; test powinien to weryfikować
- `MIGRATION_5_6` tworzy tabelę `country_flag` (opis w test-planie był błędny — opisywał 6→7)
- `MIGRATION_6_7` ma już test (`migrate6To7_addsSnapshotAndFingerprintColumns`) — nie duplikujemy
- Schema JSON musi mieć pole `identityHash` obliczone przez Room; można je uzyskać tylko przez build z tymczasowo zmodyfikowanymi entity classes

## What We're NOT Doing

- Test instrumentowany 1→2 — istnieje, tylko weryfikujemy że nie jest broken
- Rozszerzanie CI o emulator — kandydat do Phase C lub osobnej zmiany
- Testy 6→7 — istniejący test pokrywa tę migrację
- Testy JVM (Room migracje wymagają prawdziwego SQLite — tylko instrumentowane)
- Modyfikacja logiki migracji (tylko testy)

## Implementation Approach

Dwie fazy. Faza 1 jest prereqiem: bez poprawnych schema JSON plików `MigrationTestHelper`
nie może zbudować bazy w starszej wersji. Faza 2 dodaje testy korzystające z ustalonej infrastruktury.

---

## Critical Implementation Details

**Generowanie historycznych schematów v1–v6** — jednorazowe, manualne. Room oblicza `identityHash`
wyłącznie podczas buildu KSP (hasz encji w stanie N). Procedura dla każdej wersji N (1–6):

1. Tymczasowo zmień `@Database(version = N, entities = [...])` w `CciDatabase.kt` tak, żeby
   lista encji i ich pola odzwierciedlały stan schematu po migracji N–1→N:

   | v | Encje | Istotne różnice względem poprzedniej |
   |---|-------|--------------------------------------|
   | 1 | PendingCap, Binder (id, name), BinderPage (id, binder\_id, page\_number), CapPosition (id, binder\_page\_id, position, cap\_id) | baseline — bez firestore\_id |
   | 2 | jak wyżej + firestore\_id TEXT? w Binder, BinderPage, CapPosition | MIGRATION\_1\_2 |
   | 3 | CapPosition + country TEXT NOT NULL DEFAULT '' | MIGRATION\_2\_3 |
   | 4 | CapPosition bez country (przebudowana); nowa CapCache (cap\_id, country) | MIGRATION\_3\_4 |
   | 5 | CapCache + image\_url TEXT NOT NULL DEFAULT '' | MIGRATION\_4\_5 |
   | 6 | + nowa CountryFlag (name PK, image\_url) | MIGRATION\_5\_6 |

2. Uruchom `./gradlew kspDebugKotlin` → Room generuje `app/schemas/pl.sroki.cci.android.data.datasource.local.CciDatabase/N.json`
3. Skopiuj plik `N.json` do finalnego katalogu `app/schemas/…/`
4. Przywróć `CciDatabase.kt` do bieżącego stanu (version=7, wszystkie encje)
5. Powtórz dla każdej wersji 1–6, potem wygeneruj v7 finalnym buildem

**FK dependency w teście 3→4**: cap\_position ma FOREIGN KEY na binder\_page; binder\_page ma FK na binder.
Testy wstawiające dane do cap\_position muszą najpierw wstawić binder i binder\_page:
```sql
INSERT INTO binder (id, name, firestore_id) VALUES (1, 'B', null)
INSERT INTO binder_page (id, binder_id, page_number, firestore_id) VALUES (1, 1, 1, null)
INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id, country) VALUES (...)
```

---

## Phase 1: exportSchema + Schema Files

### Overview

Włącza eksport schematów Room i generuje pliki JSON dla wersji 1–7. Weryfikuje że istniejące
testy (1→2, 6→7) przechodzą z nowymi plikami.

### Changes Required

#### 1. Włączenie exportSchema

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/CciDatabase.kt`

**Intent**: Umożliwić MigrationTestHelper tworzenie bazy w dowolnej historycznej wersji.

**Contract**: Zmień `exportSchema = false` na `exportSchema = true` w `@Database` annotation (linia 23).

---

#### 2. Konfiguracja KSP i androidTest assets

**File**: `app/build.gradle`

**Intent**: Wskazać Room gdzie zapisywać schematy i udostępnić je testom instrumentowanym jako assets.

**Contract**: Dwie zmiany w bloku `android { ... }`:

```groovy
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

sourceSets {
    androidTest {
        assets.srcDirs += files("$projectDir/schemas")
    }
}
```

---

#### 3. Generowanie plików schematów

**Files**: `app/schemas/pl.sroki.cci.android.data.datasource.local.CciDatabase/1.json` – `7.json`

**Intent**: Dostarczyć MigrationTestHelper definicje schematu dla każdej wersji bazy.

**Contract**:
- Katalog: `app/schemas/pl.sroki.cci.android.data.datasource.local.CciDatabase/`
- Pliki: `1.json`, `2.json`, …, `7.json`
- v7: generowany automatycznie przez `./gradlew kspDebugKotlin` po zmianie exportSchema
- v1–v6: generowane przez 6 tymczasowych buildów (patrz Critical Implementation Details);
  każdy plik musi mieć pole `identityHash` obliczone przez Room (nie edytować ręcznie)
- Wszystkie 7 plików commitowane do repozytorium

---

### Success Criteria

#### Automated Verification

- `./gradlew kspDebugKotlin` przechodzi bez błędów z `exportSchema = true`
- `app/schemas/pl.sroki.cci.android.data.datasource.local.CciDatabase/7.json` istnieje po buildzie

#### Manual Verification

- Wszystkie 7 plików JSON (1.json–7.json) obecne w katalogu schemas/
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=pl.sroki.cci.android.data.MigrationTest` wykonuje się z 2 passing testami (istniejące 1→2 i 6→7)

**Implementation Note**: Faza 2 może startować dopiero po przejściu Manual Verification fazy 1.
Bez poprawnych schema plików testy 2→3 i kolejne będą failować na `FileNotFoundException`.

---

## Phase 2: Nowe testy migracji

### Overview

Dodaje 5 nowych metod testowych do istniejącego `MigrationTest.kt`, pokrywając wszystkie
luki w zakresie (2→3, 3→4, 4→5, 5→6) oraz łańcuchowy test 3→7.

### Changes Required

#### 1. Rozszerzenie MigrationTest

**File**: `app/src/androidTest/java/pl/sroki/cci/android/data/MigrationTest.kt`

**Intent**: Pokryć brakujące migracje i zweryfikować integralność danych w 3→4.

**Contract**: Dodaj 5 metod `@Test` do klasy `MigrationTest`, po istniejących testach,
importy do uzupełnienia: `org.junit.Assert.assertFalse`. Każdy test używa wspólnego `helper`
z `@get:Rule` i stałej `TEST_DB`. Wzorzec: `createDatabase(v)` → wstaw dane → `runMigrationsAndValidate(v+N, true, ...)` → cursor assertions.

**Testy do zaimplementowania:**

**`migrate2to3_addsCountryColumnToCapPosition`**:
- `createDatabase(TEST_DB, 2)` → wstaw binder (id=1, name='B'), binder\_page (id=1, binder\_id=1, page\_number=1), cap\_position (id=1, binder\_page\_id=1, position=1, cap\_id=10, firestore\_id=null)
- `runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)`
- `SELECT id, country FROM cap_position WHERE id = 1` → `cursor.getColumnIndexOrThrow("country")` nie rzuca; wartość == `""`

**`migrate3to4_createsCapCacheAndMigratesCountryData`**:
- `createDatabase(TEST_DB, 3)` → wstaw binder (id=1), binder\_page (id=1, binder\_id=1, page\_number=1)
- Wstaw cap\_position z `country='Poland'` (cap\_id=100) i `country=''` (cap\_id=200) — oba w tym samym binder\_page
- `runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)`
- `SELECT country FROM cap_cache WHERE cap_id = 100` → `'Poland'`
- `SELECT count(*) FROM cap_cache WHERE cap_id = 200` → `0` (pusty country nie migruje)
- `SELECT * FROM cap_position` → brak kolumny `country` (rzuca `IllegalArgumentException` przy `getColumnIndexOrThrow("country")`)

**`migrate4to5_addsImageUrlToCapCache`**:
- `createDatabase(TEST_DB, 4)` → `INSERT INTO cap_cache (cap_id, country) VALUES (5, 'France')`
- `runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)`
- `SELECT image_url FROM cap_cache WHERE cap_id = 5` → `""`

**`migrate5to6_createsCountryFlagTable`**:
- `createDatabase(TEST_DB, 5)` (brak danych — testujemy tylko strukturę)
- `runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)`
- `INSERT INTO country_flag (name, image_url) VALUES ('Poland', 'https://f.pl')` → przechodzi
- `SELECT name, image_url FROM country_flag WHERE name = 'Poland'` → oba pola poprawne
- drugi insert z `name='Poland'` rzuca wyjątek (PRIMARY KEY constraint)

**`migrateFullChain3to7_dataAndSchemaIntact`**:
- `createDatabase(TEST_DB, 3)` → wstaw binder (id=1), binder\_page (id=1, binder\_id=1, page\_number=1), cap\_position (id=1, binder\_page\_id=1, position=1, cap\_id=42, firestore\_id=null, country='Germany')
- `runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)`
- `SELECT country, image_url, name, catalog_status FROM cap_cache WHERE cap_id = 42` → country='Germany', image\_url='', name='', catalog\_status='unknown'
- `SELECT count(*) FROM country_flag` → `0` (tabela istnieje, pusta)
- `getColumnIndexOrThrow("country")` na cap\_position → rzuca (kolumna usunięta w 3→4)

---

### Success Criteria

#### Automated Verification

- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=pl.sroki.cci.android.data.MigrationTest` → wszystkie 7 testów `PASS`
- Brak `FileNotFoundException` (schema pliki poprawnie załadowane jako test assets)

#### Manual Verification

- Uruchom `MigrationTest` w Android Studio (Run > MigrationTest) i potwierdź 7 zielonych testów
- Zweryfikuj że żaden z istniejących testów (1→2, 6→7) nie zregresował

---

## Testing Strategy

### Integration Tests (instrumentowane)

- Runner: `./gradlew connectedDebugAndroidTest` z filtrem na `MigrationTest`
- Każdy test: osobna baza `TEST_DB`, izolowana przez `@Rule MigrationTestHelper` (automatycznie
  czyści po każdym teście)
- FK constraints aktywne domyślnie przez `FrameworkSQLiteOpenHelperFactory`

### Manual Testing Steps

1. Uruchom na urządzeniu/emulatorze: `Run > MigrationTest` w Android Studio
2. Zweryfikuj że wszystkie 7 metod jest zielonych
3. Sprawdź że po teście `migrate3to4` brak entry w `cap_cache` dla `cap_id=200`
4. Sprawdź że po `migrateFullChain3to7` dane 'Germany' przeżyły 4 migracje

## Performance Considerations

Testy migracji działają na prawdziwym SQLite — każdy test kilka-kilkanaście ms. Łączny czas
uruchomienia wszystkich 7 testów powinien być poniżej 10 sekund.

## References

- Test Plan: `context/foundation/test-plan.md` — Phase B (§3)
- CciDatabase + migracje: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/CciDatabase.kt:20-116`
- Istniejące testy migracji: `app/src/androidTest/java/pl/sroki/cci/android/data/MigrationTest.kt`
- DatabaseModule (rejestracja migracji): `app/src/main/java/pl/sroki/cci/android/di/DatabaseModule.kt:27-34`
- Room Testing docs: `androidx.room:room-testing:2.7.0`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: exportSchema + Schema Files

#### Automated

- [x] 1.1 `./gradlew kspDebugKotlin` przechodzi z `exportSchema = true` — 73c4520
- [x] 1.2 `app/schemas/.../7.json` istnieje po buildzie — 73c4520

#### Manual

- [x] 1.3 Wszystkie 7 plików schema JSON (1.json–7.json) present i commitowane — 73c4520
- [x] 1.4 Istniejące testy `migrate1To2` i `migrate6To7` przechodzą na urządzeniu/emulatorze — 73c4520

### Phase 2: Nowe testy migracji

#### Automated

- [x] 2.1 `./gradlew connectedDebugAndroidTest` — wszystkie 7 testów MigrationTest PASS — 1af4647

#### Manual

- [x] 2.2 Uruchom MigrationTest w Android Studio — 7 zielonych testów potwierdzone — 1af4647

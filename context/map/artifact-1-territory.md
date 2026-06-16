---
artifact: territory
generated: 2026-06-16
updated: 2026-06-16
source: git log (139 commitów) + CLAUDE.md + roadmap.md + analiza sprzężeń (sesja 2026-06-16)
note: poprzednia wersja (2026-06-10) oparta o statyczną analizę kodu bez git; zastąpiona pełną historią
---

# Artifact 1 — Territory: historia zmian i aktywne obszary

## Metadane projektu

| Pole              | Wartość                              |
|-------------------|--------------------------------------|
| `applicationId`   | `pl.sroki.cci.android`               |
| `versionCode`     | (aktualny po sentry-monitoring)      |
| `minSdk`          | 24 (Android 7.0)                     |
| `targetSdk`       | 34 (Android 14)                      |
| Backend API       | `https://crowncaps.info`             |
| Room DB version   | 7                                    |
| Łączna liczba commitów | 139                             |
| Zakres dat        | 2026-06-11 – 2026-06-15              |

---

## Oś czasu zmian (chronologicznie wg git log)

| Data       | Change ID / commit scope              | Dotknięty obszar kodu                                                              |
|------------|---------------------------------------|------------------------------------------------------------------------------------|
| 2026-06-11 | auth-scaffold (F-01) p1               | `data/datasource/remote/auth/`, `data/AuthRepository.kt`, `data/SessionRepository.kt`, `di/NetworkModule.kt` |
| 2026-06-11 | room-local-data (F-02) p1             | `data/datasource/local/` (CciDatabase v1 → entities, DAO, repos)                  |
| 2026-06-11 | firestore-sync (F-03) p1–p3           | `data/datasource/remote/firestore/`, `data/FirestoreRestoreUseCase.kt`, Room v2    |
| 2026-06-11 | home-screen-redesign (S-05) p1        | `ui/HomeScreen.kt`, `ui/home/`, `navigation/Screen.kt`, `MainActivity.kt`          |
| 2026-06-12 | advanced-search (S-06) + fixes        | `ui/catalog/caps/advanced/`, `data/AdvancedSearchPagingSource.kt`, `model/AdvancedSearch.kt` |
| 2026-06-12 | api-validation (S-07, research)       | brak zmian kodu produkcyjnego                                                      |
| 2026-06-13 | Firebase refactor (anonimowy → email) | `data/FirebaseAuthManager.kt`, `data/datasource/local/CciDatabase.kt`             |
| 2026-06-13 | fix/restore: gubione kapsle           | `data/FirestoreRestoreUseCase.kt`                                                  |
| 2026-06-14 | binder-management (S-02) + fixes      | `ui/binders/`, `data/BinderRepository.kt`, `data/BinderPageRepository.kt`          |
| 2026-06-14 | shop-check-and-mark-bought (S-01)     | `data/CapsRepository.kt`, `data/PurchasedCapsLocalStore.kt`, `ui/catalog/purchased/` |
| 2026-06-14 | cataloging-flow (S-03)                | `data/CapPositionRepository.kt`, `ui/catalog/caps/` (assign)                       |
| 2026-06-14 | binder-fill-stats (S-04)              | `ui/binders/` (stats view)                                                         |
| 2026-06-14 | cap-cache perf (Room v3→v4)           | `data/datasource/local/entity/CapCache.kt`, `data/CapCacheRepository.kt`           |
| 2026-06-14 | statistics — Kraje + Lokalizacje      | `ui/statistics/`, `data/CountriesRepository.kt`, `data/model/CountryStatRow.kt`   |
| 2026-06-14 | cap-recognition (S-09) / picture-search | `ui/catalog/picturesearch/`, `data/PictureSearchCapsPagingSource.kt`             |
| 2026-06-14 | sync: ręczna synchronizacja Firestore | `ui/HomeScreen.kt` (menu konta), `data/FirestoreRestoreUseCase.kt`                |
| 2026-06-14 | collection-resilience p1+p2           | `data/CollectionVerifier.kt`, `data/VerificationPrefs.kt`, `data/model/CapSnapshot.kt`, `data/datasource/remote/firestore/CapPositionFirestoreService.kt`, Room v5→v6 |
| 2026-06-15 | CountryFlag cache (Room v6 → implicit)| `data/datasource/local/entity/CountryFlag.kt`, `data/datasource/local/dao/CountryFlagDao.kt` |
| 2026-06-15 | CI/CD                                 | `.github/workflows/`, `app/build.gradle` (signing, Dependabot)                    |
| 2026-06-15 | test-collection-verifier-and-auth     | `test/data/CollectionVerifierTest.kt`, `test/data/FirebaseAuthManagerTest.kt`, `test/data/datasource/remote/auth/SessionAuthenticatorTest.kt`, `test/data/AuthRepositoryTest.kt` |
| 2026-06-15 | test-room-migrations                  | `androidTest/data/MigrationTest.kt`, `app/schemas/` (v1–v7 JSON), Room v7 (MIGRATION_6_7) |
| 2026-06-15 | test-firestore-restore-and-slots      | `androidTest/data/FirestoreRestoreUseCaseTest.kt`, `androidTest/data/CapPositionRepositoryTest.kt`, TOCTOU Mutex fix |
| 2026-06-15 | fix/auth: startDestination → Login    | `MainActivity.kt`                                                                  |
| 2026-06-15 | collection-stats (S-08)               | `ui/statistics/StatisticsScreen.kt`, `ui/statistics/CollectionVerificationScreen.kt` |
| 2026-06-15 | sentry-monitoring                     | `CCIApplication.kt`, `data/AuthRepository.kt` (Sentry.captureException)           |
| 2026-06-15 | fix/auth: propaguj błąd token         | `data/AuthRepository.kt`                                                           |

---

## Mapa aktywności (heat map)

### Gorące — wielokrotnie zmieniane, wysoka złożoność

| Plik                                        | Liczba commitów | Ostatnia zmiana | Uwagi                                                           |
|---------------------------------------------|-----------------|-----------------|------------------------------------------------------------------|
| `data/AuthRepository.kt`                    | 8+              | 2026-06-15      | auth scaffold → CSRF → token → Firebase → Sentry → propagacja błędu |
| `data/FirestoreRestoreUseCase.kt`           | 5+              | 2026-06-15      | initial → fix gubienia kapsli → deduplicate → TOCTOU Mutex fix |
| `data/datasource/local/CciDatabase.kt`      | 7 migracji      | 2026-06-15      | v1→v7, pełny eksport schematów JSON                            |
| `CCIApplication.kt`                         | 3+              | 2026-06-15      | startup orchestration: Firebase → Sentry → Firestore restore   |

### Ciepłe — kilka zmian, ustabilizowane

| Plik / obszar                               | Opis                                                              |
|---------------------------------------------|-------------------------------------------------------------------|
| `data/CapPositionRepository.kt`             | cataloging-flow + test-firestore-restore-and-slots (UNIQUE fix)  |
| `ui/catalog/caps/advanced/`                 | advanced-search + 6 fixów endpointów i parametrów               |
| `data/CollectionVerifier.kt` + `VerificationPrefs.kt` | collection-resilience                                |
| `data/datasource/remote/firestore/`         | 3 serwisy Firestore, kilka fixów przy restore                    |
| `data/CapsRepository.kt`                    | shop-check + PurchasedCapsLocalStore integration                 |
| `data/FirebaseAuthManager.kt`               | anonymous → email migration, testy                               |

### Chłodne — napisane raz, nieruszone

| Obszar                                      | Opis                                                              |
|---------------------------------------------|-------------------------------------------------------------------|
| `model/`                                    | Wszystkie modele domenowe (Cap, Producer, Series, CapExtended…)  |
| `data/datasource/remote/` (5 API serwisów) | CapApiService, CountryApiService, CategoryApiService, ProducerApiService, AuthApiService |
| `ui/theme/`                                 | Color.kt, Type.kt, Shape.kt, Theme.kt                            |
| `navigation/Screen.kt`                      | 14 tras — bez zmian po home-screen-redesign                      |
| `di/FirestoreModule.kt`                     | Firebase init — bez zmian                                        |
| `di/DatabaseModule.kt`                      | Room init + DAO provides — bez zmian od v1 (migracje w CciDatabase) |

---

## Stan aktywnych zmian

Katalog `context/changes/` jest **pusty** — wszystkie zmiany zarchiwizowane.

## Zarchiwizowane zmiany (`context/archive/`)

| Change ID                                  | Obszar                                         | Zamknięty   |
|--------------------------------------------|------------------------------------------------|-------------|
| 2026-06-10-api-validation                  | research, brak kodu produkcyjnego              | 2026-06-15  |
| 2026-06-11-auth-scaffold                   | auth layer + DI                                | 2026-06-15  |
| 2026-06-11-binder-management               | binder UI + repos + Firestore write-through    | 2026-06-15  |
| 2026-06-11-firestore-sync                  | Firestore services + restore use case          | 2026-06-15  |
| 2026-06-11-home-screen-redesign            | HomeScreen UI                                  | 2026-06-15  |
| 2026-06-11-room-local-data                 | Room schema v1                                 | 2026-06-15  |
| 2026-06-12-advanced-search                 | AdvancedSearch UI + paging                     | 2026-06-15  |
| 2026-06-15-binder-fill-stats               | binder stats UI                                | 2026-06-15  |
| 2026-06-15-cap-recognition                 | picture search + similar caps                  | 2026-06-15  |
| 2026-06-15-cataloging-flow                 | CapPosition UI + repo                          | 2026-06-15  |
| 2026-06-15-ci-cd                           | GitHub Actions workflow + Dependabot           | 2026-06-15  |
| 2026-06-15-collection-stats                | StatisticsScreen + collection verification     | 2026-06-15  |
| 2026-06-15-login-fix                       | MainActivity startDestination                  | 2026-06-15  |
| 2026-06-15-sentry-monitoring               | Sentry init + captureException                 | 2026-06-15  |
| 2026-06-15-shop-check-and-mark-bought      | PurchasedCapsLocalStore + UI                   | 2026-06-15  |
| 2026-06-15-test-collection-verifier-and-auth | unit tests (JVM): CollectionVerifier, FirebaseAuthManager, SessionAuthenticator, AuthRepository | 2026-06-15 |
| 2026-06-15-test-firestore-restore-and-slots | androidTests: FirestoreRestoreUseCase, CapPositionRepository | 2026-06-15 |
| 2026-06-15-test-room-migrations            | Room migration tests + schemas JSON v1–v7      | 2026-06-15  |

---

## Analiza sprzężeń — sesja 2026-06-16

Źródło: `git log --name-only` (cała historia, 139 commitów, 2026-06-10 – 2026-06-15).
Szum odfiltrowany: lockfile'y, schematy JSON, XML, Gradle configs, context/.

### TOP 10 najczęściej modyfikowanych plików

| # | Plik | Commity |
|---|------|---------|
| 1 | `ui/home/HomeScreen.kt` | 17 |
| 2 | `data/AdvancedSearchPagingSource.kt` | 17 |
| 3 | `MainActivity.kt` | 15 |
| 4 | `data/CapsRepository.kt` | 14 |
| 5 | `ui/catalog/caps/detail/CapDetailScreen.kt` | 13 |
| 6 | `data/AuthRepository.kt` | 13 |
| 7 | `ui/catalog/caps/detail/CapDetailViewModel.kt` | 12 |
| 8 | `ui/catalog/caps/advanced/AdvancedSearchScreen.kt` | 11 |
| 9 | `di/NetworkModule.kt` | 11 |
| 10 | `data/FirestoreRestoreUseCase.kt` | 10 |

### TOP 10 najczęściej modyfikowanych folderów

| # | Folder | Dotknięcia |
|---|--------|-----------|
| 1 | `data/` łącznie | 106 |
| → | `data/datasource/local/dao/` | 25 |
| → | `data/datasource/remote/` + `auth/` | 28 |
| → | `data/datasource/local/entity/` | 13 |
| 2 | `ui/catalog/caps/detail/` | 44 |
| 3 | `model/` | 37 |
| 4 | `ui/home/` | 26 |
| 5 | `ui/catalog/caps/advanced/` | 17 |
| 6 | `ui/catalog/picturesearch/` | 19 |
| 7 | `di/` | 19 |
| 8 | `ui/binders/` | 14 |
| 9 | `ui/statistics/` | 10 |
| 10 | `navigation/` | 9 |

### Nacisk pracy dzień po dniu

| Dzień | Dominujący obszar | Charakter pracy |
|-------|-------------------|-----------------|
| 2026-06-10 | `ui/catalog` (bootstrap) | Initial commit + upgrade Kotlin/KSP/Hilt |
| 2026-06-11 | `ui/catalog` + `data/datasource` | Pięć funkcji równolegle: auth, Room, Firestore, home, binders |
| 2026-06-12 | `data/AdvancedSearchPagingSource` + `ui/catalog` | Sprint wyszukiwania (15+ commitów feat/fix advanced-search) — najgorętszy dzień |
| 2026-06-13 | `data/datasource` + `ui/home` | Firebase anon→email, statystyki, bugfixing testów |
| 2026-06-14 | `data/datasource` + `ui/statistics` | Pivot ku danym: resilience, snapshot kolekcji, release keystore |
| 2026-06-15 | testy + infra | Migracje Room (7 schematów), Sentry, CI/CD, archiwizacja 10 zmian |

### Najsilniejsze sprzężenia par (co-change w tych samych commitach)

| Para | Wspólne commity |
|------|----------------|
| `data/datasource` + `ui/catalog` | **15** |
| `data/CapsRepository` + `ui/catalog` | **12** |
| `data/CapsRepository` + `data/datasource` | **10** |
| `data/CapPositionRepository` + `data/datasource` | **9** |
| `MainActivity` + `navigation/Screen` | **9** |
| `data/datasource` + `di/NetworkModule` | **7** |
| `data/datasource` + `di/DatabaseModule` | **7** |
| `data/FirestoreRestoreUseCase` + `data/datasource` | **7** |

### Najsilniejsze trójki

| Trójka | Wspólne commity |
|--------|----------------|
| `data/CapsRepository` + `data/datasource` + `ui/catalog` | **8** |
| `data/CapPositionRepository` + `data/datasource` + `ui/catalog` | **7** |
| `MainActivity` + `ui/catalog` + `ui/home` | **6** |
| `MainActivity` + `navigation/Screen` + `ui/home` | **6** |

### Wspólny mianownik — plik cross-cutting

`model/CapExtended.kt` — zmieniony tylko **5 razy**, ale z najwyższym ratio fan-out (11.4 unikalnych obszarów per commit). Każda jego edycja wywołuje lawinę zmian w wielu warstwach jednocześnie. `navigation/Screen.kt` drugi (ratio 5.8) — każda nowa trasa dotyka wielu ekranów.

### Implikacje architektoniczne

- **Ripple effect:** trójka `data/CapsRepository + data/datasource + ui/catalog` w 8 commitach oznacza, że zmiany kontraktu API przebiegają przez wszystkie trzy warstwy bez buforu. Wyodrębnienie interfejsu lub mappera w `CapsRepository` przyniosłoby największy zwrot.
- **`MainActivity` jako hub nawigacyjny:** 9 wspólnych commitów z `navigation/Screen` sugeruje, że nawigacja jest zbyt sklejona z Activity hostem.
- **`model/` jest zdrowy:** nie pojawia się w TOP 20 par — granica modelu domenowego jest dobrze zdefiniowana.

### Weryfikacja istnienia kluczowych plików

Sprawdzono 18 plików z analizy: **17/18 istnieje**.

Usunięty: `data/PurchasedCapsPagingSource.kt` — celowo wyeliminowany w commicie `c2d3213` (`feat(purchased): lokalny store ID-ów kupionych kapsli zamiast filtrowania API`). Zastąpiony lokalnym cache `CapPosition`. Sprzężenie tego pliku z `data/CapsRepository` przestało istnieć wraz z nim.

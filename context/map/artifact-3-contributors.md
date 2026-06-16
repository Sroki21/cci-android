---
artifact: contributors
generated: 2026-06-16
source: git log --format="%H %ae %s" + git shortlog + analiza wzorców commitów
note: poprzednia wersja (2026-06-10) oparta o sygnały w kodzie bez git; zastąpiona danymi z historii
---

# Artifact 3 — Contributors: kontekst kontrybutorów

## Profil kontrybutorów

| Autor | Email               | Commity | Aktywność           |
|-------|---------------------|---------|---------------------|
| Sroki | sroki21@gmail.com   | 139     | 2026-06-11 – 2026-06-15 |

**Projekt ma jednego kontrybutora** — właściciela i developera jednocześnie.

---

## Wzorce pracy (wnioskowane z historii commitów)

### AI-assisted workflow (10x toolkit)

Autor stosuje konsekwentny cykl planowania:
```
/10x-plan → /10x-implement (phase n) → /10x-impl-review → fixes → /10x-archive
```

Wzorce w commitach:
- `feat(<id>): ... (p1)`, `feat(<id>): ... (p2)` — fazy implementacji
- `chore(<id>): close out plan (epilogue)` — zamknięcie planu przed archiwizacją
- `chore(<id>): impl-review fixes (F1+F2+F3)` — poprawki po code review
- `chore(archive): close <id>` — archiwizacja

### Konwencja commitów

Conventional Commits z polskim opisem:
- Typy: `feat`, `fix`, `chore`, `test`, `perf`, `refactor`, `build`, `docs`
- Scope = change-id (`sentry-monitoring`, `test-room-migrations`) lub domenowy (`auth`, `binders`, `statistics`)
- Opisy techniczne po polsku

### Tempo i intensywność

| Dzień      | Liczba commitów | Główny temat                                     |
|------------|-----------------|--------------------------------------------------|
| 2026-06-11 | ~20             | auth-scaffold + room-local-data + firestore-sync |
| 2026-06-12 | ~15             | advanced-search + api-validation                 |
| 2026-06-13 | ~8              | Firebase refactor + auth fixes                   |
| 2026-06-14 | ~40             | binder-management + S-01/S-03/S-04 + statistics + resilience |
| 2026-06-15 | ~56             | testy + CI/CD + sentry + archiwa + finalizacja   |

Łącznie: **5 dni roboczych**, ~28 commitów/dzień.

---

## Ekspertyza wnioskowana z historii

### Obszary płynne — first-pass bez fixów

| Obszar | Dowód |
|--------|-------|
| Room schema + migracje | F-02 i test-room-migrations bez regresji; eksport schematów poprawny |
| Paging 3 integration | 6 PagingSource, wszystkie z poprawną logiką prevKey/nextKey |
| Hilt DI wiring | 3 moduły DI, constructor injection wszędzie, żadne błędy linkownia |
| Jetpack Compose | 14 ekranów, LazyColumn, LazyVerticalGrid, Pager — spójne wzorce |
| Firestore read/write | Write-through + restore pattern — solidny od pierwszej iteracji |

### Obszary wymagające iteracji (multi-commit fixes)

**Auth layer — 8+ commitów na `AuthRepository.kt`:**
1. CSRF cookie handling (Laravel Sanctum + PersistentCookieJar)
2. OkHttp redirect handling — osobny klient bez followRedirects (302 to sukces)
3. ResponseBody parsing — `ResponseBody.string()` zamiast typed `LoginResponse`
4. Firebase anonymous → email migration (usunięcie anonimowego auth)
5. Bearer token fetch + cache w SessionRepository
6. Sentry `captureException` integration
7. Propagacja błędu `fetchApiToken` zamiast połykania

**Firestore restore — 5+ commitów:**
1. Initial restore flow (CCIApplication.onCreate)
2. Fix gubienia kapsli przy restore
3. TOCTOU race condition → `Mutex` w `restoreIfEmpty`
4. Deduplication klaserów po nazwie (chooseBinders)
5. Wywołanie restoreIfEmpty po logowaniu (AuthRepository.login)

### Stosunek do testów

Podejście: **najpierw implementacja, testy jako osobne change'y na końcu** (3 osobne change'y z testami na liście):

**JVM (unit tests) — `app/src/test/`:**
- `CollectionVerifierTest` — 7 przypadków (R1)
- `FirebaseAuthManagerTest` — 3 testy (R3)
- `SessionAuthenticatorTest` (R6)
- `AuthRepositoryTest` (R6)
- `CapsRepositoryTest`
- `LatestCapsPagingSourceTest`
- `HomeViewModelTest`
- `BindersViewModelTest`

**Instrumentowane (androidTest) — `app/src/androidTest/`:**
- `MigrationTest` — migracje 2→7
- `FirestoreRestoreUseCaseTest` — restore + TOCTOU
- `CapPositionRepositoryTest` — slot uniqueness (FR-012)
- `BinderRepositoryTest`
- `FirestoreWriteThroughTest`
- `FirestoreRestoreTest`
- `PendingCapDaoTest`

---

## Decyzje architektoniczne widoczne w kodzie

| Decyzja | Plik | Uzasadnienie |
|---------|------|--------------|
| Dwa klienty OkHttp | `NetworkModule.kt` | Auth OkHttp bez followRedirects — 302 po POST /auth/login = sukces; śledzenie redirect nadpisywałoby sesję |
| `PersistentCookieJar` | `NetworkModule.kt` | Laravel Sanctum wymaga persystencji `XSRF-TOKEN` + `crowncapsinfo-session` między sesjami |
| `Mutex` w `restoreIfEmpty` | `FirestoreRestoreUseCase.kt` | TOCTOU — równoległe wywołanie z CCIApplication i z AuthRepository.login |
| `user-locale=pl` network interceptor | `NetworkModule.kt` | API zwraca polskie nazwy krajów przy tym locale |
| `productId=1` auto-append | `NetworkModule.kt` | Domyślny filtr produktu dla list kapsli; nie dotyczy endpointów kolekcji (osobna gałąź) |
| `fallbackToDestructiveMigration` | `DatabaseModule.kt` | Apka prywatna, dane odtwarzalne z Firestore — brak ryzyka utraty |
| `@Named("auth")` Retrofit | `NetworkModule.kt` | Izolacja interceptorów auth od głównego klienta |
| Brak Timber | `AuthRepository.kt`, itp. | Aktualna implementacja używa `android.util.Log`; Timber odnotowany jako brak w roadmap baseline |

---

## Preferowane wzorce kodu

- **Constructor injection** we wszystkich repozytoriach → umożliwia MockK w testach JVM
- **`runTest` + MockK** dla testów jednostkowych
- **In-memory Room** dla testów instrumentowanych DAO
- **`StateFlow`** jako exposer stanu ViewModel (nie LiveData)
- **`collectAsState()`** w Composables zamiast `observeAsState()`
- **`sealed interface RestoreResult`** — typed return type zamiast Boolean/Exception

---

## Obszary rekomendowane do szczególnej uwagi

Oparte na liczbie iteracji i złożoności logiki:

1. **`AuthRepository.kt`** — wielokrotnie iterowany; auth jest najsłabszym ogniwem (cookie + bearer + Firebase + Sentry + dwa klienty OkHttp)
2. **`FirestoreRestoreUseCase.kt`** — operacja destruktywna (`deleteAll` + kaskada); Mutex chroni, ale wywołanie z dwóch miejsc (Application + AuthRepository) wymaga uwagi
3. **`NetworkModule.kt`** — dwa klienty z różnymi interceptorami; kolejność interceptorów ma znaczenie; `productId=1` jest "ukrytym" zachowaniem sieciowym
4. **`CciDatabase.kt`** — `fallbackToDestructiveMigration` jest bezpieczne tylko gdy Firestore restore działa poprawnie; testy migracji (MigrationTest) są kluczowe przed każdym bump wersji

---

## Analiza kontrybutorów wg obszarów ryzyka — sesja 2026-06-16

Źródło: `git log --since="12 months ago"` (140 commitów) + filtrowanie sygnatur AI.

### Filtrowanie sygnatur

| Sygnatura | Typ | Liczba | Decyzja |
|-----------|-----|--------|---------|
| `Sroki <sroki21@gmail.com>` | człowiek (primary author) | 140 commitów | **zachowany** |
| `Claude Sonnet 4.6 <noreply@anthropic.com>` | agent AI | 120 co-authorships | odfiltrowany |
| `Claude Opus 4.8 <noreply@anthropic.com>` | agent AI | ~11 co-authorships | odfiltrowany |

120 z 140 commitów ma co-autorstwo Claude (Sonnet 4.6 lub Opus 4.8) — we wszystkich przypadkach primary author to Sroki. AI działa jako asystent, nie niezależny kontrybutor.

**Bus factor: 1.** Projekt nie ma żadnych innych ludzkich kontrybutorów.

### Sroki — aktywności wg obszarów ryzyka

#### Obszar 1 — Auth layer (`AuthRepository.kt` + `NetworkModule.kt`) — ~17 commitów

| Temat | Przykłady |
|-------|-----------|
| Scaffolding warstwy sieciowej | `networking foundation — OkHttp + CookieJar + CSRF + AuthApiService` |
| Diagnoza i naprawa CSRF/Sanctum | `dodaj naglowek Referer dla Sanctum stateful session auth` |
| Dwa klienty OkHttp | `osobny OkHttpClient bez followRedirects dla endpointow auth` |
| Bearer token + sesja | `bearer token z SharedPreferences + naprawa isInCollection` |
| Firebase anonymous → email | `usunięcie anonymous auth + migracja danych między UID` |
| Obsługa błędów i Sentry | `Sentry SDK, init, user context i caught exceptions`, `propaguj błąd fetchApiToken` |

Sroki zna szczegóły Laravel Sanctum (cookie + CSRF), powód `followRedirects = false` i ewolucję Firebase auth.

#### Obszar 2 — `FirestoreRestoreUseCase.kt` — ~8 commitów

| Temat | Przykłady |
|-------|-----------|
| Inicjalna architektura | `write-through sync`, `initial restore — FirestoreRestoreUseCase + CCIApplication` |
| Naprawa gubienia danych | `odtwarzaj komplet danych z Firestore bez gubienia kapsli` |
| TOCTOU race condition | `Firestore tests + TOCTOU fix Mutex` |
| Ręczna synchronizacja | `ręczna synchronizacja klaserów z Firestore z menu konta` |
| Testy | `slot uniqueness coverage FR-012`, `Firestore tests + TOCTOU fix Mutex` |

Sroki osobiście napotkał race condition między `CCIApplication` a `AuthRepository` i zaprojektował Mutex guard. Zna kontrakt danych Firestore od strony zapisu i odczytu destruktywnego.

#### Obszar 3 — Backend API (`crowncaps.info` + `CapApiService`) — ~15 commitów

| Temat | Przykłady |
|-------|-----------|
| Odkrywanie kontraktu API | `popraw filtr produktu: productId=1 (nie product_id=2)` |
| Lokalizacja odpowiedzi | `polska nazwa kraju w szczegółach kapsla`, `user-locale=pl fix` |
| Parametry endpointów | `globalny filtr product_id=2`, `producer fallback na scalony ?query=` |
| Toggle kolekcji | `poprawny endpoint toggle kolekcji crowncaps.info` |
| Obejścia client-side | `GET z in_collection pomija productId=1, filtr client-side` |

Sroki empirycznie odkrył zachowanie API (wiele fix-commitów przed ustaleniem poprawnych parametrów). Wie które endpointy ignorują filtry i wymagają obejść client-side. Brak drugiej osoby znającej kontrakt serwera.

#### Obszar 4 — `CciDatabase.kt` migracje Room — ~10 commitów

| Temat | Przykłady |
|-------|-----------|
| Schema v1 i fundament | `Room schema — entities, DAOs, repositories, in-memory tests` |
| Kolejne migracje (v2–v6) | `Firebase infra + Room migration v1→v2`, `cap_cache extract + UNIQUE idx` |
| Naprawa UNIQUE constraint | `wyczyść Room przed migracją`, `pomiń duplikaty przez insertOrIgnore` |
| Schema JSON + testy | `exportSchema + 7 schema JSONs`, `nowe testy migracji 2→7` |
| Snapshot v7 | `cap_cache snapshot fields + last_verified_at + catalog_status` |

Sroki przeprowadził 7 migracji bez utraty danych (Firestore jako źródło prawdy + `fallbackToDestructiveMigration` jako siatka bezpieczeństwa). Zna pułapki UNIQUE constraint przy `insertOrIgnore`.

#### Obszar 5 — `model/CapExtended.kt` (cross-cutting) — ~5 commitów, fan-out 11+

| Temat | Powiązane commity |
|-------|-------------------|
| Material 3 migration | `Material 1 → Material 3 across entire UI layer` |
| kotlinx-datetime → stdlib | `migrate to kotlin.time.Instant` |
| Status kapsla | `zmiana statusu kapsla z zapisem do crowncaps.info` |
| Pola snapshot | `cap_cache snapshot fields + last_verified_at + catalog_status` |

`CapExtended` był modyfikowany jako efekt uboczny innych zmian — nie miał własnego change-a. Sroki wie jakie pola są serializowane do API, jakie są UI-only, i które zmiany powodują cascade po 11 obszarach.

### Konkluzja

Jedynym kandydatem do supportu we wszystkich 5 obszarach jest Sroki. Żaden obszar nie ma bus factor > 1. Szczególnie wrażliwe są obszary 1 (auth) i 3 (API contract) — wymagają wiedzy tacit której nie ma w kodzie, jedynie w historii commitów i pamięci autora.

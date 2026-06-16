---
artifact: structure
generated: 2026-06-16
updated: 2026-06-16
source: MainActivity.kt, CCIApplication.kt, NetworkModule.kt, DatabaseModule.kt, FirestoreModule.kt, CciDatabase.kt, Screen.kt, FirestoreRestoreUseCase.kt, AuthRepository.kt + dependency-analysis-gradle-plugin 3.15.0
note: poprzednia wersja (2026-06-10) opisywała MVP bez auth/Room/Firestore; zastąpiona pełną strukturą
---

# Artifact 2 — Structure: zależności, entry pointy, cykle i lokalne centra

## Entry pointy aplikacji

| Entry point      | Plik                     | Rola                                                                                   |
|------------------|--------------------------|----------------------------------------------------------------------------------------|
| `CCIApplication` | `CCIApplication.kt`      | `@HiltAndroidApp`; Sentry init; Firebase signIn; Firestore restore w tle               |
| `MainActivity`   | `MainActivity.kt`        | Single activity; `@AndroidEntryPoint`; `@Inject SessionRepository`; NavHost z 14 trasami |

---

## Graf nawigacji (NavHost — 14 tras)

```
startDestination:
  ├── Screen.Login          gdy SessionRepository.loadCachedToken() == null
  └── Screen.Home           gdy token istnieje

Screen.Home
  ├── Screen.Countries      → Screen.Country(countryId, name)
  │                               └── Screen.CapDetail(capId)  [terminal]
  ├── Screen.PictureSearch  → Screen.CapDetail(capId)
  ├── Screen.QuickSearchResults(query)  → Screen.CapDetail(capId)
  ├── Screen.Latest         → Screen.CapDetail(capId)
  ├── Screen.AdvancedSearch → Screen.CapDetail(capId)
  ├── Screen.Purchased      → Screen.CapDetail(capId)
  ├── Screen.Binders        → Screen.CapDetail(capId)  [po capId: Int]
  ├── Screen.Login          (z Home gdy wylogowany)
  ├── Screen.CollectionVerification  → Screen.CapDetail(capId)
  ├── Screen.Statistics
  │     ├── Screen.OwnedCountries   → Screen.CountryOwnedCaps(country)
  │     └── Screen.LocationsMap     → Screen.CountryOwnedCaps(country)
  └── Screen.CountryOwnedCaps(country)  → Screen.CapDetail(capId)
```

`Screen.CapDetail` jest węzłem terminalnym — osiągany z 7 różnych ścieżek.

---

## Moduły Hilt DI

### NetworkModule (`di/NetworkModule.kt`) — @InstallIn(SingletonComponent)

```
PersistentCookieJar (Singleton)
  └── SetCookieCache + SharedPrefsCookiePersistor

SessionAuthenticator (Singleton/Authenticator)
  ← CookieJar + SessionRepository

OkHttpClient (Singleton) — główny klient
  interceptory (kolejność):
    1. AcceptJsonInterceptor
    2. BearerTokenInterceptor(SessionRepository)
    3. CsrfInterceptor(CookieJar)
    4. produktId=1 auto-append (dla /api/v1/caps lists)
  networkInterceptor:
    5. user-locale=pl fix
    6. HttpLoggingInterceptor (tylko DEBUG)
  authenticator: SessionAuthenticator

OkHttpClient @Named("auth") (Singleton) — auth klient
  interceptory: AcceptJson, BearerToken, Csrf
  followRedirects = false   ← kluczowe: 302 po POST /auth/login to sukces
  (brak productId interceptor, brak SessionAuthenticator)

Retrofit (Singleton) ← OkHttpClient główny
Retrofit @Named("auth") (Singleton) ← OkHttpClient @Named("auth")

Serwisy API (Singleton):
  CapApiService          ← Retrofit
  CountryApiService      ← Retrofit
  CategoryApiService     ← Retrofit
  ProducerApiService     ← Retrofit
  AuthApiService         ← Retrofit @Named("auth")
```

### DatabaseModule (`di/DatabaseModule.kt`) — @InstallIn(SingletonComponent)

```
CciDatabase (Singleton) — Room v7, plik: cci.db
  migracje: MIGRATION_1_2 … MIGRATION_6_7
  fallbackToDestructiveMigration (bezpieczne: dane odtwarzalne z Firestore)
  
  DAO (nie Singleton — nowe instancje per CciDatabase):
    PendingCapDao   → pending_cap
    BinderDao       → binder
    BinderPageDao   → binder_page
    CapPositionDao  → cap_position
    CapCacheDao     → cap_cache
    CountryFlagDao  → country_flag
```

### FirestoreModule (`di/FirestoreModule.kt`) — @InstallIn(SingletonComponent)

```
FirebaseAuth (Singleton)
FirebaseFirestore (Singleton)
FirebaseAuthManager (Singleton) ← FirebaseAuth
BinderFirestoreService (Singleton) ← FirebaseFirestore
BinderPageFirestoreService (Singleton) ← FirebaseFirestore
CapPositionFirestoreService (Singleton) ← FirebaseFirestore
```

---

## Warstwa danych — mapa zależności repozytoriów

```
AuthRepository (@Singleton)
  ← AuthApiService
  ← SessionRepository
  ← PersistentCookieJar
  ← FirebaseAuthManager
  ← FirestoreRestoreUseCase

FirestoreRestoreUseCase (@Singleton)
  ← FirebaseAuthManager         (uid.value — brama dostępu)
  ← CciDatabase                 (withTransaction)
  ← BinderDao, BinderPageDao, CapPositionDao, CapCacheDao
  ← BinderFirestoreService, BinderPageFirestoreService, CapPositionFirestoreService
  posiada: restoreIfEmptyMutex (Mutex) — TOCTOU guard

BinderRepository (@Singleton)
  ← BinderDao + BinderFirestoreService

BinderPageRepository (@Singleton)
  ← BinderPageDao + BinderFirestoreService + BinderPageFirestoreService

CapPositionRepository (@Singleton)
  ← CapPositionDao + CapPositionFirestoreService + CapCacheDao

PendingCapRepository (@Singleton)
  ← PendingCapDao

CapsRepository (@Singleton)
  ← CapApiService + PendingCapRepository + CapCacheDao
  tworzy: 6 PagingSource (Latest, QuickSearch, Country, Advanced, Similar, PictureSearch)

CapCacheRepository (@Singleton)
  ← CapCacheDao

CountriesRepository (@Singleton)
  ← CountryApiService + CountryFlagDao

CollectionVerifier (@Singleton)
  ← CapCacheDao + CapPositionDao + CapApiService + VerificationPrefs

PurchasedCapsLocalStore (standalone)
  ← SharedPreferences

ProducersRepository (@Singleton)
  ← ProducerApiService

CategoriesRepository (@Singleton)
  ← CategoryApiService
```

---

## Schemat bazy Room v7 (CciDatabase)

```
pending_cap
  id (PK), cap_id, name, image_url, country, added_at

binder
  id (PK AUTOINCREMENT), name (UNIQUE — constraint), firestore_id
  CASCADE DELETE → binder_page

binder_page
  id (PK AUTOINCREMENT), binder_id (FK → binder CASCADE), page_number, firestore_id
  CASCADE DELETE → cap_position

cap_position
  id (PK AUTOINCREMENT), binder_page_id (FK → binder_page CASCADE),
  position, cap_id, firestore_id
  UNIQUE(binder_page_id, position) ← FR-012 slot uniqueness

cap_cache
  cap_id (PK), country, image_url, name,
  created_at (TEXT/Instant), created_by_id, updated_at,
  last_verified_at (INTEGER/epoch), catalog_status (TEXT, DEFAULT 'unknown')

country_flag
  name (PK), image_url
```

Historia migracji: 1→2 (firestore_id), 2→3 (cap_position.country), 3→4 (cap_cache extract + cap_position rebuild + UNIQUE idx), 4→5 (cap_cache.image_url), 5→6 (country_flag), 6→7 (cap_cache snapshot fields + last_verified_at + catalog_status).

---

## Lokalne centra (węzły o najwyższej łączności)

### 1. `SessionRepository` — centrum sesji
- Wstrzykiwany przez: `AuthRepository`, `NetworkModule` (2× — BearerTokenInterceptor + SessionAuthenticator), `MainActivity`
- Wrażliwy punkt: każda zmiana przepływu auth lub interceptorów dotyka tego singletona

### 2. `CciDatabase` — centrum danych lokalnych
- Używany przez: `DatabaseModule` (6 DAO), `FirestoreRestoreUseCase` (transakcje)
- Wrażliwy punkt: migracje — każda nowa encja = bump wersji + migracja SQL + eksport schematu JSON + test migracji

### 3. `FirebaseAuthManager` — centrum tożsamości Firebase
- Wstrzykiwany przez: `AuthRepository`, `FirestoreRestoreUseCase`, `CCIApplication`
- Wrażliwy punkt: `uid: StateFlow<String?>` — null blokuje dostęp do Firestore; musi być ustawiony przed wywołaniem useCases

### 4. `CapApiService` — centrum dostępu do katalogu
- Używany przez: 6 PagingSource (przez CapsRepository) + `CollectionVerifier`
- Wrażliwy punkt: zmiany w API crowncaps.info propagują się do wszystkich widoków paginowanych

### 5. `FirestoreRestoreUseCase` — centrum restorecji (destruktywna operacja)
- Wstrzykiwany przez: `AuthRepository` (po logowaniu), `CCIApplication` (przy starcie)
- Wrażliwy punkt: `restoreFromFirestore()` robi `binderDao.deleteAll()` + kaskadowe usunięcie + re-insert; bezpieczny tylko gdy Firestore ma dane. `restoreIfEmptyMutex` chroni przed race condition między CCIApplication a AuthRepository.

---

## Cykle zależności

**Brak cykli w grafie DI.** Architektura jest acykliczna:

```
Composable → ViewModel → Repository → DataSource (Room/Retrofit/Firestore)
```

Sprzężenia wsteczne (nie są cyklami DI — używają field injection):
- `CCIApplication` → `FirebaseAuthManager` + `FirestoreRestoreUseCase` (field injection @Inject lateinit)
- `AuthRepository` → `FirestoreRestoreUseCase` (constructor injection — Hilt umie rozwiązać)

---

## Model warstw

```
┌──────────────────────────────────────────────────┐
│  UI: Composable + @HiltViewModel                 │
│  14 ekranów, 10+ ViewModeli                      │
├──────────────────────────────────────────────────┤
│  Domain: Repository + UseCase + PagingSource     │
│  12 repozytoriów + FirestoreRestoreUseCase       │
│  6 PagingSource (wszystkie w CapsRepository)     │
├──────────────────────────────────────────────────┤
│  Data:                                           │
│  Remote: 5 Retrofit API serwisów               │
│  Local: 6 Room DAO (CciDatabase v7)             │
│  Cloud: 3 Firestore serwisy                     │
├──────────────────────────────────────────────────┤
│  Infra:                                          │
│  OkHttp (2 klienty) + PersistentCookieJar       │
│  Firebase Auth + Firestore                       │
│  Sentry (obserwability)                          │
│  DI: Hilt (3 moduły: Network, Database, Firestore) │
└──────────────────────────────────────────────────┘
```

---

## Analiza zależności — sesja 2026-06-16

Narzędzie: `com.autonomousapps.dependency-analysis` v3.15.0 (wymagała upgrade z 2.10.0 — niezgodność kotlin-metadata-jvm z Kotlin 2.3.x; naprawione od v3.2.0).
Konfiguracja: `build.gradle` (root) + `app/build.gradle`; raport: `build/reports/dependency-analysis/build-health-report.txt`.
Wynik: BUILD SUCCESSFUL — naruszenia jako `warn`, nie `error`.

### Nieużywane zależności — ocena

| Zależność | Ocena | Uzasadnienie |
|-----------|-------|--------------|
| `kotlinx-datetime:0.8.0` impl | **Usuń** | git log: "migrate to kotlin.time.Instant" — migracja wykonana, biblioteka zbędna |
| `espresso-core:3.7.0` androidTest | **Usuń** | CLAUDE.md: preferuj `onNodeWithText()` nad Espresso dla Compose; testy są Compose-only |
| `lifecycle-runtime-ktx:2.10.0` impl | **Zbadaj** | `lifecycleScope` może przychodzić tranzytywnie z `activity-compose`; sprawdź importy |
| `sentry-android:7.22.6` impl | **Zbadaj** | Umbrella artifact — faktyczne klasy są w `sentry-android-core` (transitive); plugin sugeruje zadeklarować core bezpośrednio |
| `room-ktx:2.7.0` impl | **Zostaw** | False positive — wymagany dla suspend fun w DAO; bez niego Room nie obsługuje coroutines |
| `core-ktx:1.19.0` impl | **Zostaw** | Prawdopodobnie false positive — KTX extensions używane pośrednio przez wiele komponentów |
| `paging-testing:3.5.0` testImpl | **Zostaw** | Potrzebny jeśli testy używają `TestPager`; weryfikacja przez grep |
| `ui-test-junit4` androidTest | **Zostaw** | Wymagany do `createComposeRule()` — plugin nie widzi użycia przez refleksję w ComposeTestRule |
| `mockk-android:1.14.11` androidTest | **Zostaw** | Inicjalizacja mockk na Android wymaga tego artefaktu nawet bez bezpośrednich importów |

### Tranzytywne zależności używane bezpośrednio — priorytety

Plugin zgłosił 55 pozycji. Zdecydowana większość to wewnętrzne artefakty Compose BOM lub Hilt — deklarowanie ich wszystkich zaszumiłoby `build.gradle`. Warte rozważenia:

| Zależność | Priorytet | Powód |
|-----------|-----------|-------|
| `kotlinx-coroutines-core:1.11.0` impl | **Wysoki** | Używana bezpośrednio w repozytoriach (suspend, Flow, Mutex); deklaracja chroni przed zmianą w Kotlin stdlib |
| `okhttp3:okhttp:4.12.0` impl | **Wysoki** | Używana wprost w `NetworkModule` (OkHttpClient.Builder); transitive z `logging-interceptor` |
| `kotlinx-coroutines-core:1.11.0` testImpl | **Wysoki** | `runTest`, `TestScope` — wprost w testach |
| `okhttp3:okhttp:4.12.0` testImpl | **Średni** | Jeśli testy mockują OkHttp bezpośrednio |
| `javax.inject:javax.inject:1` impl | **Niski** | `@Inject` pochodzi przez Hilt — zmiana Hilt pociągnie za sobą zmianę tego też |
| Compose internal (runtime, ui-*, animation) | **Pomiń** | Zarządzane przez Compose BOM; deklaracja wprost odpina je od BOM-a |

### Zastosowane zmiany (2026-06-16)

Zweryfikowane grep-em przed usunięciem; build przeszedł po `--write-locks`.

**Usunięto:**
- `implementation 'org.jetbrains.kotlinx:kotlinx-datetime:0.8.0'` — `Clock.System.now()` importuje `kotlin.time.Clock` ze stdlib (Kotlin 2.x); migracja z kotlinx-datetime zakończona
- `androidTestImplementation 'androidx.test.espresso:espresso-core:3.7.0'` — brak importów Espresso; testy są Compose-only (`createComposeRule`)

**Dodano wprost:**
- `implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0'` — używana bezpośrednio we wszystkich repozytoriach; explicite przypina 1.11.0 zamiast 1.7.3–1.9.0 wciąganych przez Navigation/Compose
- `testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0'` — wymagana przez `runTest`/`TestScope` w testach JVM
- `implementation 'com.squareup.okhttp3:okhttp:4.12.0'` — używana wprost w 8 plikach (`Interceptor`, `ResponseBody`, `MultipartBody`, `HttpUrl`); wcześniej transitive z `logging-interceptor`

**Zaktualizowano:**
- `app/gradle.lockfile` — `--write-locks` po zmianie grafu zależności

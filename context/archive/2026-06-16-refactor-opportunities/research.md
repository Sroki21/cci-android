---
date: 2026-06-16T12:00:00+02:00
researcher: Claude Sonnet 4.6 (3 równoległe sub-agenty)
git_commit: 998f38d84a8237d6c594bb2c97069fae86dd1bbc
branch: main
repository: cci-android
topic: "Refactor opportunities — klasyfikacja i ranking kandydatów strukturalnych"
tags: [research, refactor, firestore-restore, auth, mapping, architecture, verified]
status: complete
last_updated: 2026-06-16
last_updated_by: Claude Sonnet 4.6
verified_commit: 998f38d84a8237d6c594bb2c97069fae86dd1bbc
---

# Research: Refactor opportunities

**Date**: 2026-06-16T12:00:00+02:00
**Researcher**: Claude Sonnet 4.6 (3 równoległe sub-agenty)
**Git Commit**: 998f38d84a8237d6c594bb2c97069fae86dd1bbc
**Branch**: main
**Repository**: cci-android

## Research Question

Na podstawie `context/changes/firestore-restore-flow/research.md` (dług techniczny i ryzyka strukturalne) oraz `context/map/repo-map.md` (mapa sprzężeń i stref ryzyka): które z odnotowanych problemów warto naprawić, w jakim docelowym kształcie i w jakiej kolejności? Metodologia: eksploracja kodu (obecny kształt) + archeologia git (intencjonalność) + analiza wykonalności (testy, CI, blast radius). Żadnych zmian w kodzie.

---

## Priory i metoda

Analiza buduje na ustaleniach dwóch wcześniejszych artefaktów:
- `context/changes/firestore-restore-flow/research.md` — dług techniczny TD-1..TD-8, blast radius, open questions
- `context/map/repo-map.md` — 6 stref ryzyka, sprzężenia co-change z git (139 commitów, 5 dni)

**CI (potwierdzone):** `.github/workflows/android.yml` uruchamia wyłącznie `testDebugUnitTest` + `ktlintCheck`. Testy instrumentowane (`androidTest/`) **NIE są chronione przez CI**.

**Sub-agenty (read-only):**
1. Obecny kształt — odczyt plików + grep, file:line evidence
2. Historia i intencjonalność — git log, git show, commit messages
3. Wykonalność migracji — testy, istniejące abstrakcje, blast radius

---

## Klasyfikacja problemów

**Kryteria:** KANDYDAT = naprawa zmieniłaby strukturę kodu produkcyjnego. Nie-kandydat = brakujący test, luka w dokumentacji, problem procesowy.

### Nie-kandydaci (testy, dokumentacja, procesy)

| Problem | Etykieta | Powód wykluczenia |
|---------|----------|-------------------|
| `chooseBinders()` bez unit testu | TD-1 | Brakujący test |
| `uid==null` gałęzie nieobjęte testami | TD-2 | Brakujące testy |
| Firestore exception brak testu | TD-3 | Brakujący test |
| `restoreFromFirestore()` brak testu success path | TD-4 | Brakujący test |
| `Thread.sleep(500)` w teście integracyjnym | TD-5 | Jakość testu, nie kodu prod |
| Orphaned items nieobjęte testami | TD-6 | Brakujące testy |
| `HomeViewModel.confirmSync()` brak testu | TD-7 | Brakujący test |
| `fallbackToDestructiveMigration` + Firestore offline = utrata danych | Open Q4, Strefa 4 | Ukryte sprzężenie arch.; wymaga ADR i testu, nie refaktoru kodu |
| API contract `crowncaps.info` bez spec | Strefa 5 | Dokumentacja/proces |
| Bus factor 1 | Strefa 6 | Proces/zespół |

### Kandydaci (K1–K10)

| ID | Problem | Źródło |
|----|---------|--------|
| K1 | Brak interfejsu dla `FirestoreRestoreUseCase` | TD-8 |
| K2 | Inline mapping w `fetchAll()` serwisów Firestore — brak klasy mapper | research |
| K3 | Asymetryczny Mutex: `restoreIfEmpty` chroniony, `restoreFromFirestore` nie | research |
| K4 | `restoreIfEmpty()` z 2 niepowiązanych call-sites bez centralnego koordynatora | research |
| K5 | `AuthRepository` — 3 ortogonalne mechanizmy auth w jednej klasie | Strefa 1 |
| K6 | `CapExtended.kt` — model domenowy z fan-out ×11 | Strefa 3 |
| K7 | `CapsRepository` — jedyny hub dla 6 PagingSource | co-change |
| K8 | `data/datasource` + `ui/catalog` — brak buforu między API a UI | co-change |
| K9 | `MainActivity` + `navigation/Screen` — nawigacja sklejona z Activity hostem | co-change |
| K10 | `NetworkModule` + datasource — nowy endpoint = zmiany w obu | co-change |

---

## Wyniki per kandydat

### K1 — Brak interfejsu dla `FirestoreRestoreUseCase`

**Obecny kształt** [evidence]:
- `FirestoreRestoreUseCase.kt:30` — `class FirestoreRestoreUseCase @Inject constructor(...)` — konkretna klasa
- `CCIApplication.kt:20` — `@Inject lateinit var firestoreRestoreUseCase: FirestoreRestoreUseCase`
- `AuthRepository.kt:22` — `private val firestoreRestoreUseCase: FirestoreRestoreUseCase`
- `HomeViewModel.kt:40` — `private val firestoreRestoreUseCase: FirestoreRestoreUseCase`
- `FirestoreRestoreUseCase.kt:23` — `sealed interface RestoreResult` istnieje (kontrakt dla zwracanego wyniku)
- Grep na `interface FirestoreRestoreUseCase` — 0 wyników [evidence]

Odpowiedzialności mieszają się: NIE. UseCase jest spójny (fetch z 3 serwisów Firestore → deduplikacja → insert do Room). Problem dotyczy wyłącznie brakującej indirekcji dla testowania i podmiany implementacji.

**Intencjonalność:** przypadkowa złożoność
- Commit `30c31f1` 2026-06-11 — klasa stworzona od razu jako `@Singleton @Inject constructor` bez interfejsu
- Testy od początku używają `mockk(relaxed = true)` na konkretnej klasie — brak interfejsu nigdy nie blokował
- Brak sygnału że ktokolwiek rozważał alternatywę i świadomie odrzucił
- Klasyczne YAGNI: interfejs nie był potrzebny, więc nie powstał

**Wykonalność migracji:**
- Testy CI (unit): BRAK — żadne testy jednostkowe nie testują UseCase
- Testy instrumentowane (poza CI): TAK — `FirestoreRestoreUseCaseTest.kt` (2 testy: rollback + concurrent)
- Istniejące abstrakcje: `RestoreResult` sealed interface (wzorzec znany autorowi)
- Blast radius: 4 pliki (FirestoreRestoreUseCase.kt, CCIApplication.kt, AuthRepository.kt, HomeViewModel.kt) + opcjonalnie FirestoreRestoreUseCaseTest.kt
- Złożoność migracji: NISKA — zmiana addytywna, brak zmian zachowania
- Odwracalność: ŁATWA

---

### K2 — Inline mapping w serwisach Firestore

**Obecny kształt** [evidence]:
- `BinderFirestoreService.kt:29-35` — `fetchAll()` mapuje 2 pola inline (`firestoreId`, `name`) → `BinderDocument`
- `BinderPageFirestoreService.kt:30-37` — `fetchAll()` mapuje 3 pola inline (`binderFirestoreId`, `pageNumber`, docId) → `BinderPageDocument`
- `CapPositionFirestoreService.kt:65-84` — `fetchAll()` mapuje 5 pól + **warunkowe odtworzenie snapshotu**:
  - linię 67-76: jeśli `capImageUrl != null`, konstruuje `CapSnapshot(6 pól)` z defensywnymi domyślnymi
  - logika warunkowa (guard na `capImageUrl`) + 6 pól snapshot jest **odrębną odpowiedzialnością** mieszaną z fetchem

Grep na `Mapper|mapper|Converter|converter` w `data/datasource/` — 0 wyników [evidence]. Żadna klasa mapper nie istnieje.

Odpowiedzialności mieszają się: TAK (tylko w `CapPositionFirestoreService`) — fetch dokumentów Firestore + warunkowa rekonstrukcja `CapSnapshot` z danych `capImageUrl`.

**Intencjonalność:** przypadkowa złożoność
- Commit `46912cc` 2026-06-11 — wszystkie 3 serwisy stworzone jednocześnie z mappingiem inline
- Commit `3468cdf` 2026-06-13 — "feat(collection-resilience): snapshot" rozszerzył `CapPositionFirestoreService.fetchAll()` o dodatkowe pola bez wydzielenia mappera
- Wzorzec: inline był rozsądny dla 2-3 pól, ale nie był zrewidowany gdy mapping urósł do ~10 pól + logiki warunkowej

**Wykonalność migracji:**
- Testy CI (unit): BRAK — Firestore serwisy nie mają testów jednostkowych
- Testy instrumentowane (poza CI): TAK — `FirestoreRestoreUseCaseTest.kt` mockuje wszystkie 3 serwisy
- Istniejące abstrakcje: brak mapperów; modele docelowe istnieją (`BinderDocument`, `BinderPageDocument`, `CapPositionDocument`, `CapSnapshot`)
- Blast radius: 3 pliki (`BinderFirestoreService.kt`, `BinderPageFirestoreService.kt`, `CapPositionFirestoreService.kt`) + nowe klasy mapper
- Złożoność migracji: NISKA (czysta ekstrakcja, brak zmiany zachowania; snapshot guard w `CapPositionFirestoreService` wymaga precyzji przy przenoszeniu nullability)
- Odwracalność: ŁATWA

---

### K3 — Asymetryczny Mutex

**Obecny kształt** [evidence]:
- `FirestoreRestoreUseCase.kt:41` — `private val restoreIfEmptyMutex = Mutex()`
- `FirestoreRestoreUseCase.kt:47-56` — `restoreIfEmpty()` używa `restoreIfEmptyMutex.withLock { ... }` przed sprawdzeniem `countAll()`
- `FirestoreRestoreUseCase.kt:64-78` — `restoreFromFirestore()` wywołuje 3 identyczne `fetchAll()` (linię 67-69) **bez ochrony mutex**
- Obie metody wywołują identyczne serwisy Firestore: `binderService.fetchAll(uid)`, `binderPageService.fetchAll(uid)`, `capPositionService.fetchAll(uid)`

Latentna race condition: `restoreFromFirestore()` (wywołana przez `HomeViewModel.confirmSync()`) może biec równolegle z `restoreIfEmpty()` (wywoływaną z `CCIApplication.onCreate()` lub `AuthRepository.login()`). `restoreFromFirestore()` wykonuje `database.withTransaction { deleteAll() + insertRestored() }` — jeśli `restoreIfEmpty` wchodzi w fazę `insertRestored()` jednocześnie, Room transakcja i mutex nie są ze sobą skoordynowane. [inference]

**Intencjonalność:** świadome ograniczenie — ale częściowo
- Commit `3c1d674` 2026-06-14 — "TOCTOU fix Mutex" — mutex dodany **celowo** do `restoreIfEmpty` po zidentyfikowaniu race condition w teście `concurrentCalls`
- Research.md wyjaśnia: "Synchronizacja leży po stronie HomeViewModel" dla `restoreFromFirestore` bo jest wyłącznie user-triggered
- Uzasadnienie jest słuszne dla scenariusza: 2x `restoreIfEmpty` równolegle
- **Ale:** nie pokrywa scenariusza `restoreIfEmpty` + `restoreFromFirestore` równolegle (startup + user sync button)
- Werdykt: decyzja była świadoma w odniesieniu do TOCTOU między dwoma `restoreIfEmpty`, ale nie rozważała cross-method race

**Wykonalność migracji:**
- Testy CI (unit): BRAK (testy UseCase są instrumentowane)
- Testy instrumentowane (poza CI): TAK — `FirestoreRestoreUseCaseTest.kt` linia 111-132: `concurrentCalls_noDuplicates` testuje mutex behavior; test dodany intencjonalnie z komentarzem wyjaśniającym dlaczego `runBlocking` (nie `runTest`)
- Zmiana: opakowanie `restoreFromFirestore()` w `restoreIfEmptyMutex.withLock { ... }` — **1 linia**
- Blast radius: 1 plik (`FirestoreRestoreUseCase.kt`) + opcjonalnie nowy test dla mixed concurrent call
- Złożoność migracji: BARDZO NISKA — 1 linia, brak zmiany sygnatury, brak zmiany DI
- Odwracalność: TRYWIALNA
- Jedyne ryzyko: `withLock` spowoduje, że user sync button "poczeka" jeśli startup restore jest w toku — semantycznie poprawne, ale dodaje latencję UI na edge case

---

### K4 — Dual callsite `restoreIfEmpty()` bez koordynatora

**Obecny kształt** [evidence]:
- `CCIApplication.kt:34` — `firestoreRestoreUseCase.restoreIfEmpty()` w `applicationScope` (startup, automatyczny)
- `AuthRepository.kt:59` — `firestoreRestoreUseCase.restoreIfEmpty()` po `signInWithEmail()` w `login()` (post-login, user action)
- `SessionRepository.kt` — tylko stan (SharedPreferences + StateFlow), nie koordynator
- Brak klasy koordynatora auth initialization [evidence]

Odpowiedzialności mieszają się: NIE. Każdy callsite ma jasną intencję. Problem dotyczy **rozproszenia orkiestracji**.

**Intencjonalność:** świadome ograniczenie
- Commit `30c31f1` 2026-06-11 — oba callsity powstały jednocześnie (startup + AuthRepository)
- Commit `b544852` 2026-06-12 — commit message wprost uzasadnia drugi callsite: "wywołujemy restoreIfEmpty(). Dzięki temu UID jest stały [...] dane przeżywają reinstalację"
- Dual callsite to celowe pokrycie dwóch edge case'ów: (1) cold start bez logowania, (2) login po cold starcie gdzie UID się zmienił
- Mutex chroni przed podwójną restauracją

**Wykonalność migracji:**
- Złożoność: ŚREDNIA — centralizacja przez `uid.collectLatest {}` wymaga zarządzania lifecycle (applicationScope vs inne scope)
- Testy: BRAK unit testów dla startup logic
- Ryzyko: HIGH — jeśli observer źle skonfigurowany (zły scope, podwójne kolekcje), bug nie zostanie wychwycony przez CI
- ROI: NISKIE — Mutex już obsługuje podwójne wywołanie (drugie wraca natychmiast przez `countAll() > 0`)
- Rekomendacja: **pominąć** — świadoma decyzja, dobrze zabezpieczona Mutexem, refaktor dodaje ryzyko bez wartości

---

### K5 — `AuthRepository` — 3 ortogonalne mechanizmy auth

**Obecny kształt** [evidence]:
- `AuthRepository.kt` — 106 linii, @Singleton, 5 wstrzykniętych zależności
- Mechanizm 1 — **Laravel Sanctum (cookie + CSRF)**:
  - `AuthRepository.kt:40` — `authApiService.initCsrf()`
  - `AuthRepository.kt:41` — `authApiService.login(LoginRequest(...))`
  - `AuthRepository.kt:43` — odczyt cookie z `PersistentCookieJar`
  - Stan: `PersistentCookieJar` (HTTP cookies)
- Mechanizm 2 — **Firebase email/password**:
  - `AuthRepository.kt:56` — `firebaseAuthManager.signInWithEmail(email, password)`
  - Stan: `FirebaseAuth` (UID w StateFlow)
- Mechanizm 3 — **Bearer token (API)**:
  - `AuthRepository.kt:51` — `fetchApiToken(email, password)` → `SessionRepository.setToken()`
  - `AuthRepository.kt:78-97` — prywatna metoda `fetchApiToken()` z `AuthApiService.apiToken()`
  - Stan: `SessionRepository` (SharedPreferences)
- Każdy mechanizm ma **własny store stanu** — 3 niezależne systemy stanu w jednej klasie [evidence]

Odpowiedzialności mieszają się: TAK — 3 auth flows, 3 state stores, niejawna kolejność (Sanctum → Firebase → Bearer).

**Intencjonalność:** przypadkowa złożoność
- `56dbcb6` 2026-06-10 — klasa stworzona z Sanctum + Bearer (2 mechanizmy od dnia 0)
- `b544852` 2026-06-12 — Firebase dodane jako **fix stabilności UID**, nie jako zaplanowana architektura
- 8+ iteracji bugfixów każdego mechanizmu przez 3 dni — każdy mechanizm iterował osobno
- Brak ADR; brak commita który planuje trójmechanizmową architekturę z góry
- Klasa rosła reaktywnie; każda iteracja naprawiała jeden mechanizm, nie przeglądała całości

**Wykonalność migracji:**
- Testy CI (unit): TAK — `AuthRepositoryTest.kt` 6 testów (raport: 9) pokrywających: init (cookie check, no-cookie, no-cookie-with-token), login success, login 422, logout
- Testy instrumentowane: BRAK
- Wstrzyknięcia w ViewModels: `LoginViewModel.kt`, `HomeViewModel.kt`
- Istniejące abstrakcje: `FirebaseAuthManager.kt` istnieje (zarządza UID StateFlow) — naturalne miejsce do przeniesienia email auth flow
- Blast radius: AuthRepository.kt, AuthRepositoryTest.kt, LoginViewModel.kt, HomeViewModel.kt, nowy `AuthModule.kt` lub rozszerzenie `NetworkModule.kt` — **5-6 plików**
- Złożoność migracji: ŚREDNIA
- Kluczowe ryzyko: `sessionRepository` jest wspólnym stanem dla wszystkich 3 mechanizmów — split bez koordynatora przesuwa problem, nie rozwiązuje
- Wstępny krok-prerekwizyt: **ustalenie wzorca koordynatora** (kto ustawia `sessionRepository.isLoggedIn`?) zanim którykolwiek mechanizm zostanie wydzielony

---

### K6 — `CapExtended.kt` fan-out ×11

**Obecny kształt** [evidence]:
- `CapExtended.kt` — 61 linii, 40 pól, 13 importowanych typów domenowych
- Annotacje: `@Immutable`, `@Serializable` (2 custom serializers: `IsInCollectionSerializer`, `InstantSerializer`)
- Konwersje jako extension functions: `toSnapshot()` (linię 42-49), `toCap()` (linię 51-60)
- Pola: 10 skalarnych, 4 zagnieżdżone obiekty domenowe, 6 list domenowych, 4 opcjonalne, 2 annotacje serializacji
- Unikalne pliki referencujące: `CapDetailViewModel.kt`, `CapDetailView.kt`, `CapApiService.kt`, `CapsRepository.kt`, `AdvancedSearchPagingSource.kt` — minimum 5 [evidence]

**Intencjonalność:** świadome ograniczenie
- Commit `e3523bf` 2026-06-10 — model miał 25+ pól od dnia 0, `@Immutable @Serializable` od początku
- Model jest bezpośrednim odbiciem odpowiedzi REST API `crowncaps.info/caps/{id}`
- Aplikacja nie kontroluje kształtu odpowiedzi serwera
- `@Serializable` + brak warstwy mapowania to świadomy wybór minimalizacji złożoności dla single-dev projektu
- Fan-out (użycie przez wiele ekranów) jest konsekwencją, nie przyczyną problemu

**Wykonalność migracji:**
- Złożoność: WYSOKA — identyfikacja których pól są faktycznie używane w `CapDetailView`, tworzenie projekcji `CapDetail`, aktualizacja VM + View
- Testy CI (unit): BRAK testów dla `CapExtended` ani `CapDetailView/CapDetailViewModel`
- Kluczowe ryzyko: brak testu wykryjącego błąd projekcji (pominięte pole = crash runtime)
- Werdykt: to jest pytanie o domenę biznesową ("czy model może być węższy?"), nie o strukturę kodu — zakres późniejszej analizy jeśli pojawi się konkretna potrzeba

---

### K7 — `CapsRepository` jako jedyny hub PagingSource

**Obecny kształt** [evidence]:
- `CapsRepository.kt:27-43` — 6 factory methods tworzących PagingSource
- `CapsRepository.kt` — 105 linii; zawiera ZARÓWNO metody zapytań (getById, getLatest, getByQuery…) JAK I fabryki PagingSource
- Wszystkie 6 PagingSource przyjmuje `CapsRepository` w konstruktorze i wywołuje jego metody w `load()`
- 11+ ViewModels wstrzykuje `CapsRepository` [evidence]

**Intencjonalność:** świadome ograniczenie
- `e3523bf` 2026-06-10 — klasa była hubem od dnia 0: 4 fabryki PagingSource w initial commit
- Wzorzec Paging3 + Hilt: `Repository → PagingSource.load() → Repository.get*()` to idiom opisany w dokumentacji Android
- Hub jest konsekwencją jednego backendu (`CapApiService`) — podział na N repozytoriów bez N backends nie redukuje złożoności

**Wykonalność migracji:**
- Złożoność: ŚREDNIA — interfejs `CapsPagingSourceFactory` jest możliwy, ale nie redukuje coupling (PagingSource nadal woła z powrotem do CapsRepository)
- Werdykt: nie jest problemem; idiom Paging3 — pominąć

---

### K8 — Brak buforu między `data/datasource` a `ui/catalog`

**Obecny kształt** [evidence]:
- `CapApiService.kt` zwraca `CapExtended` bezpośrednio
- `CapsRepository.kt:61-63` — `getById()` przekazuje `CapExtended` bez mapowania
- UI (`CapDetailView.kt`, `CapDetailViewModel.kt`) bezpośrednio używa modelu `CapExtended`
- Konwersja: extension function `CapExtended.toCap()` (nie dedykowana klasa mapper)
- Room entities (`Binder`, `BinderPage`, `CapPosition`) używane bezpośrednio w `BindersScreen.kt`, `BindersViewModel.kt`

**Intencjonalność:** świadome ograniczenie
- CLAUDE.md projektu jawnie opisuje trójwarstwową architekturę: `Composable → ViewModel → Repository` — brak use-case layer to udokumentowana decyzja
- 15 co-changed commitów odzwierciedla naturalną propagację zmiany API przez warstwy, nie brak abstrakcji

**Wykonalność migracji:**
- Złożoność: ŚREDNIA — wymaga UI model projections per entity + extension functions + aktualizacja 6-8 plików
- Werdykt: encje domenowe powinny być eksponowane do UI (to jest ich rola); refaktor byłby over-engineeringiem przy tej skali — pominąć

---

### K9 — `MainActivity` + nawigacja sklejone z Activity hostem

**Obecny kształt** [evidence]:
- `MainActivity.kt:70-248` — composable `Navigation()` zawiera pełny NavHost z 15 route handlerami (176 linii)
- `Screen.kt:6-43` — sealed class z 13 obiektami nawigacji
- Standardowa Single-Activity architecture (Jetpack Navigation Compose)

**Intencjonalność:** świadome ograniczenie
- CLAUDE.md wprost instruuje: "NavHost w `MainActivity.kt` — nowe trasy dodawaj tam"
- Single-Activity Compose to pattern Google Android Architecture Guide
- Coupling jest opisany jako zamierzona architektura, nie zastana sytuacja

**Wykonalność migracji:** NISKA potrzeba
- Werdykt: Android standard, kosmetyczna zmiana bez realnego decoupling — pominąć

---

### K10 — `NetworkModule` z logiką biznesową

**Obecny kształt** [evidence]:
- `NetworkModule.kt:68-111` — `provideOkHttpClient()` zawiera:
  - `NetworkModule.kt:73-94` — logikę biznesową: `capsDetailRegex`, filtracja `productId`, nagłówek `user-locale=pl` wstrzyknięte inline w interceptor HTTP
- Wzorzec interceptorów już istnieje: `AcceptJsonInterceptor`, `BearerTokenInterceptor`, `CsrfInterceptor` — osobne klasy
- Logika z linii 73-94 **nie ma własnej klasy interceptora** mimo że pozostałe zachowania sieciowe ją mają

**Intencjonalność:** przypadkowa złożoność (częściowo)
- `56dbcb6`, `5670917`, `30324b7` — NetworkModule rósł przez 4 kolejne commity bugfixów auth
- Centralizacja Retrofit jest świadoma (udokumentowana w CLAUDE.md)
- Ale: specyficzna logika `productId + user-locale + capsDetailRegex` weszła inline podczas iteracyjnego debugowania, nie jako zaplanowany interceptor
- Naruszenie własnej reguły CLAUDE.md: "jeden Retrofit client" — faktycznie istnieją dwa klienty

**Wykonalność migracji:**
- Złożoność: NISKA-ŚREDNIA — ekstrakcja logiki z linii 73-94 do nowej klasy `CapDetailInterceptor` (lub `CrowncapsBusinessInterceptor`)
- Wzorzec istnieje: `AcceptJsonInterceptor` i `CsrfInterceptor` pokazują jak to zrobić
- Testy CI: BRAK unit testów dla NetworkModule
- Blast radius: `NetworkModule.kt` + nowy plik interceptora — **2 pliki**
- Odwracalność: ŁATWA

---

## Refactor Opportunities — Ranking

### #1: K3 — Asymetryczny Mutex → symetryczny

**Obecny kształt → docelowy:**
`restoreIfEmpty()` chronione Mutex; `restoreFromFirestore()` bez ochrony → obydwie metody chronione tym samym `restoreIfEmptyMutex`.

**Dlaczego to miejsce:**
- Najniższy stosunek koszt-zmiany / koszt-długu: **1 linia kodu** zamyka latentną race condition
- `restoreFromFirestore()` może biec równolegle z `restoreIfEmpty()` (startup + user sync button) — `database.withTransaction` chroni atomowość Room, ale nie serializuje operacji z perspektywy UseCase; ryzyko real
- Istniejący test `concurrentCalls_noDuplicates` (`FirestoreRestoreUseCaseTest.kt:111-132`) waliduje mutex behavior i wyjaśnia dlaczego `runBlocking` (nie `runTest`) jest tu wymagany — test istnieje i można go rozszerzyć o mixed concurrent call
- Intencjonalność: **świadome ograniczenie**, ale niekompletne — decyzja obejmowała tylko `restoreIfEmpty` vs `restoreIfEmpty`, nie `restoreIfEmpty` vs `restoreFromFirestore`

**Blast radius:** 1 plik (`FirestoreRestoreUseCase.kt`) + opcjonalny nowy test

**Szkic inkrementalnej ścieżki:**
1. Dodaj `restoreIfEmptyMutex.withLock { }` wokół ciała `restoreFromFirestore()` (po warunku `!= null`)
2. Dodaj test `concurrentCalls_restoreIfEmpty_and_restoreFromFirestore_noDataLoss`
3. Uruchom istniejące testy instrumentowane — zweryfikuj brak regresji

**Pierwszy krok-prerekwizyt:** żaden — zmiana jest samowystarczalna i addytywna.

---

### #2: K2 — Inline mapping → ekstrakted mapper dla `CapPositionFirestoreService`

**Obecny kształt → docelowy:**
Warunkowa logika odtwarzania `CapSnapshot` (6 pól, guard na `capImageUrl`) w `CapPositionFirestoreService.fetchAll()` linię 67-76 → ekstrakcja do klasy `CapPositionMapper` (lub extension function `QueryDocumentSnapshot.toCapPositionDocument()`).

**Dlaczego to miejsce:**
- Jedyna z 3 usług Firestore, gdzie mapping **przekroczył próg prostoty**: 5 pól + conditional 6-field snapshot construction (łącznie ~12 linii logiki w środku fetchAll)
- `BinderFirestoreService` (2 pola) i `BinderPageFirestoreService` (3 pola) mogą pozostać inline — ich mapping jest trywialny i czytelny; wydzielanie ich byłoby over-engineeringiem
- Przypadkowa złożoność: mapper nie powstał gdy snapshot logic weszła w commit `3468cdf`, bo projekt był w trybie sprint
- Ekstrakcja jest **czysto addytywna** — nowy plik lub extension function, stary kod usuwany po weryfikacji
- Null-safety guard (`capImageUrl` jako decydent o całym snapshot) jest kluczowym invariantem — wydzielony mapper czyni go widocznym i testowalnym osobno

**Blast radius:** `CapPositionFirestoreService.kt` + nowy plik mappera — **2 pliki**; `FirestoreRestoreUseCaseTest.kt` nie wymaga zmian (mockuje fetchAll na poziomie serwisu)

**Szkic inkrementalnej ścieżki:**
1. Ekstrakcja: stwórz `CapPositionMapper.kt` z funkcją `fun QueryDocumentSnapshot.toCapPositionDocument(): CapPositionDocument?`
2. Przenieś logikę linię 65-84 do mappera, zachowując dokładnie te same null checks
3. Zastąp inline code wywołaniem mappera w `fetchAll()`
4. Uruchom testy instrumentowane (`FirestoreRestoreTest.kt`) — powinny przejść bez zmian

**Pierwszy krok-prerekwizyt:** przeczytać `CapPositionFirestoreService.kt:65-84` i zrozumieć dokładnie semantykę `capImageUrl == null → cały snapshot null`; mapper musi zachować ten invariant. Bez weryfikacji semantyki guard — nie zaczynać.

---

### #3: K5 — `AuthRepository` — ekstrakcja mechanizmu Firebase email auth

**Obecny kształt → docelowy:**
3 ortogonalne mechanizmy auth (Sanctum + Firebase + Bearer) w jednej klasie → **pierwszy krok**: wydzielenie odpowiedzialności Firebase email/password do `FirebaseAuthManager.kt` (który już istnieje jako klasa zarządzająca UID StateFlow).

**Dlaczego to miejsce:**
- Najwyższy ongoing cost of debt: 8+ iteracji bugfixów przez 3 dni, każdy mechanizm iterował osobno, interakcje między mechanizmami nie były planowane
- `FirebaseAuthManager.kt` **już istnieje** jako singleton zarządzający UID — email `signInWithEmail(email, password)` naturalnie należy tam, nie do `AuthRepository`
- `AuthRepositoryTest.kt` pokrywa 9 testów jednostkowych — zmiana jest chroniona przez CI (jedyny kandydat z ochroną CI po stronie unit testów)
- Przypadkowa złożoność: Firebase doszło jako fix ("logujemy się do Firebase tymi samymi danymi"), nie jako zaplanowana warstwa
- Uwaga: pełna dekompozycja na 3 klasy wymaga decyzji o koordynatorze (`sessionRepository` jest wspólnym stanem) — to osobna decyzja planistyczna

**Blast radius:** `AuthRepository.kt`, `AuthRepositoryTest.kt`, `FirebaseAuthManager.kt`, `FirebaseAuthManagerTest.kt` (jeśli powstanie) — **3-4 pliki**

**Szkic inkrementalnej ścieżki (pierwszy krok, nie pełna dekompozycja):**
1. Przenieś `signInWithEmail(email, password)` z `AuthRepository.kt:56` do `FirebaseAuthManager.kt` jako publiczna metoda (lub upewnij się że istniejąca metoda `FirebaseAuthManager.signInWithEmail` jest już wystarczająca)
2. Zaktualizuj `AuthRepository.login()` by delegować do `firebaseAuthManager.signInWithEmail(email, password)` (bez inlinowania logiki Firebase w AuthRepository)
3. Zaktualizuj `AuthRepositoryTest.kt` — mockk `firebaseAuthManager` powinien obsłużyć ten krok naturalnie (zależność już jest wstrzyknięta)

**Pierwszy krok-prerekwizyt:** Przeczytać `FirebaseAuthManager.kt` w całości — sprawdzić czy `signInWithEmail(email, password)` już istnieje i jaka jest jej sygnatura. Jeśli tak, krok 1 to tylko usunięcie duplikatu w `AuthRepository`. Decyzja o wzorcu koordynatora dla sesji (`sessionRepository.isLoggedIn`) zapada **osobno**, przed drugą iteracją.

---

## Kandydaci rozważeni i odrzuceni

| Kandydat | Powód odrzucenia |
|----------|-----------------|
| **K1 — Interfejs dla FirestoreRestoreUseCase** | Niskie ROI: mockk działa na klasach; interfejs podnosi testowalność marginalnie. Żadna z 3 osób nie potrzebuje podmienić implementacji. Rozważyć jeśli pojawi się potrzeba no-op restore w testach UI. |
| **K4 — Dual callsite bez koordynatora** | Świadome ograniczenie (commit `b544852` z uzasadnieniem). Mutex obsługuje podwójne wywołanie poprawnie. Centralizacja przez `uid.collectLatest {}` dodaje ryzyko lifecycle bez wartości: startup path nie ma unit testów w CI. |
| **K6 — `CapExtended` fan-out** | Świadome ograniczenie: model bezpośrednio odzwierciedla API response. Fan-out jest właściwością domeny, nie błędem projektowym. Naprawa wymagałaby przeprojektowania pojęć biznesowych — zakres oddzielnej analizy, nie refaktoru kodu. |
| **K7 — `CapsRepository` hub** | Świadome ograniczenie: idiom Paging3. Hub jest poprawny dla jednego backendu. 4 unit testy w `CapsRepositoryTest.kt` potwierdzają że wzorzec jest testowalny. |
| **K8 — Brak buforu API↔UI** | Świadome ograniczenie: CLAUDE.md dokumentuje trójwarstwową architekturę bez use-case layer. Encje domenowe (`Binder`, `CapExtended`) słusznie trafiają do UI przez ViewModel. Projekcja na BinderUiModel dodałaby 3 pliki bez redukcji coupling. |
| **K9 — Nawigacja w MainActivity** | Świadome ograniczenie: Android standard single-activity + NavHost. Documented w CLAUDE.md. Wydzielenie NavGraph composable to kosmetyczne (ActivityActivity nadal owned NavController). |
| **K10 — NetworkModule inline logic** | Częściowa przypadkowa złożoność. Wzorzec interceptora istnieje (`AcceptJsonInterceptor`, `CsrfInterceptor`). **Jednak:** brak unit testów dla NetworkModule sprawia że ekstrakcja jest niechroniona przez CI; priorytet niższy niż K3 i K2. Warto rozważyć w drugiej iteracji po K3 i K2. |

---

## Weryfikacja twierdzeń (ast-grep)

Weryfikacja przeprowadzona na commicie `998f38d` (2026-06-16). Narzędzia: ast-grep 0.43.0 + grep fallback dla przypadków gdzie wzorzec zwracał exit 1.

| # | Twierdzenie | Werdykt | Dowód (plik:linia) | Metoda (wzorzec/reguła) |
|---|------------|---------|---------------------|------------------------|
| C1 | `restoreIfEmptyMutex = Mutex()` w FirestoreRestoreUseCase.kt:41 | POTWIERDZONE | FirestoreRestoreUseCase.kt:41 | ast-grep: `private val $VAR = Mutex()` |
| C2 | `withLock` wyłącznie w `restoreIfEmpty()` (linia 49) | POTWIERDZONE | FirestoreRestoreUseCase.kt:49 | ast-grep: `$MUTEX.withLock { $$$ }` |
| C3 | `withLock` nieobecny w `restoreFromFirestore()` | POTWIERDZONE | grep "withLock" w FirestoreRestoreUseCase.kt: wynik wyłącznie linia 49 | grep "withLock" |
| C4 | Obie metody wywołują fetchAll na tych samych 3 serwisach (linie 51–53 i 67–69) | POTWIERDZONE | linie 51–53 (wewnątrz withLock, restoreIfEmpty), linie 67–69 (poza withLock, restoreFromFirestore) | ast-grep: `$SERVICE.fetchAll($UID)` |
| C5 | 3 callsity konkretnej klasy FirestoreRestoreUseCase | POTWIERDZONE | CCIApplication.kt:20, AuthRepository.kt:22, HomeViewModel.kt:40 | grep "FirestoreRestoreUseCase" (ast-grep zwrócił exit 1 dla wzorca Kotlin property declaration) |
| C6 | Guard `capImageUrl` + 6 pól snapshot w CapPositionFirestoreService.kt:67–76 | POTWIERDZONE | CapPositionFirestoreService.kt:67–76 | ast-grep: `doc.getString("capImageUrl")?.let { $$$ }` |
| C7 | `AuthRepository.kt:56` — delegacja `firebaseAuthManager.signInWithEmail(email, password)` | DOPRECYZOWANE | AuthRepository.kt:56: `runCatching { firebaseAuthManager.signInWithEmail(email, password) }` — wywołanie opakowane w `runCatching`, nie bare call; FirebaseAuthManager.signInWithEmail już istnieje jako `suspend fun signInWithEmail(email, password)` na FirebaseAuthManager.kt:24 | grep + ast-grep |
| C8 | `AuthRepositoryTest.kt` ma 9 testów | OBALONE | AuthRepositoryTest.kt: linie 72, 86, 96, 106, 119, 135 → **6 testów** | ast-grep: `@Test` (6 dopasowań), potwierdzone grep |
| C9 | CapsRepository.kt:27–43 — 6 factory methods PagingSource | POTWIERDZONE (zakres 27–43 zawiera też `searchSimilar` linie 36–38, niebędący fabryką PagingSource — count 6 poprawny) | linie 27, 28, 29, 30–31, 33–34, 40–43 | grep "PagingSource\|pagingSource" (ast-grep zwrócił exit 1 dla wzorca factory) |
| C10 | AuthRepository.kt ma 106 linii | POTWIERDZONE | wc -l = 106 | wc -l |

### Uwagi weryfikacyjne

**C8 (OBALONE — do decyzji na etapie planowania):** Faktyczna liczba testów w `AuthRepositoryTest.kt` to **6**, nie 9. Pokrycie: 3 testy init (cookie exists, no-cookie, no-cookie-with-token) + login success + login 422 + logout. Redukuje ochronę CI dla K5, ale nie dyskwalifikuje kandydata — 6 testów nadal chroni przed regresją.

**C7 (DOPRECYZOWANE — do decyzji na etapie planowania):** Weryfikacja ujawniła, że `FirebaseAuthManager.signInWithEmail(email, password)` **już istnieje** (`FirebaseAuthManager.kt:24`) i `AuthRepository.kt:56` **już deleguje** do niej przez `runCatching { firebaseAuthManager.signInWithEmail(email, password) }`. Oznacza to, że krok 1 ścieżki inkrementalnej K5 w rankingu (#3) — ekstrakcja `signInWithEmail` do `FirebaseAuthManager` — jest **już wykonany**. Rzeczywistym zakresem K5 pozostaje warunkowa logika Sanctum init block (`AuthRepository.kt:40–44`) oraz prywatna metoda `fetchApiToken()` (`linie 78–97`). Pozycja K5 w rankingu nie zmienia się; zmienia się opis pierwszego kroku.

---

## Code References

- `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt:41` — Mutex declaration
- `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt:47-56` — restoreIfEmpty() z Mutex
- `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt:64-78` — restoreFromFirestore() bez Mutex
- `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/CapPositionFirestoreService.kt:65-84` — inline mapping + conditional snapshot logic
- `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt:40-97` — 3 mechanizmy auth
- `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt:56` — Firebase email auth (K5 first extract target)
- `app/src/main/java/pl/sroki/cci/android/data/FirebaseAuthManager.kt` — istniejące miejsce dla K5 extraction
- `app/src/androidTest/java/pl/sroki/cci/android/data/FirestoreRestoreUseCaseTest.kt:111-132` — test concurrent calls (K3 guard)
- `app/src/test/java/pl/sroki/cci/android/data/AuthRepositoryTest.kt` — 6 testów CI (raport: 9) (K5 guard)
- `.github/workflows/android.yml` — CI: testDebugUnitTest + ktlintCheck (brak testów instrumentowanych)

---

## Historical Context (from prior changes)

- `context/changes/firestore-restore-flow/research.md` — pełna ścieżka e2e, TD-1..TD-8, blast radius, open questions. Traktowane jako zebrane dowody; nie wyprowadzane na nowo.
- `context/map/repo-map.md` — 6 stref ryzyka, sprzężenia co-change z git (139 commitów). Źródło K5–K10.

---

## Related Research

- `context/changes/firestore-restore-flow/research.md` — poprzedni research (prior)

---

## Open Questions

1. **K5 — koordynator SessionRepository:** Przed ekstrakcją mechanizmu Firebase z AuthRepository należy odpowiedzieć: kto jest odpowiedzialny za finalne ustawienie `sessionRepository.isLoggedIn`? Mechanizm cookie (init block) i mechanizm Bearer (init block) mogą ustawiać je niezależnie. Decyzja o koordynatorze jest prerekrewizitem dla pełnej dekompozycji K5.

2. **K10 — drugi OkHttpClient:** CLAUDE.md mówi "jeden Retrofit client dla całej aplikacji", ale `NetworkModule.kt` zawiera dwa klienty (główny + auth bez followRedirects). To naruszenie własnej reguły. Jeśli pojawi się trzeci przypadek wymagający odmiennej konfiguracji HTTP, warto rozważyć ekstrakcję logiki biznesowej (K10) razem z wyjaśnieniem reguły w CLAUDE.md.

3. **Testy instrumentowane poza CI:** 4 z 10 kandydatów (K1, K2, K3, K4) ma wyłącznie testy instrumentowane jako safety net. Decyzja o dodaniu ich do CI (np. przez emulator w GitHub Actions) byłaby prerekrewizitem do bezpieczniejszej migracji tych obszarów — ale to osobny zakres.

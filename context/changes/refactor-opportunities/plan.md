# Refactor Opportunities — Implementation Plan

## Overview

Trzy niezależne, inkrementalne refaktory strukturalne wprowadzone w jednym branchu (commit na fazę): zamknięcie latentnej race condition (K3), ekstrakcja nadmiarowej logiki mappera (K2) i przeniesienie logiki pobierania tokenu Bearer do właściwego właściciela (K5). Każda faza jest self-contained — można ją zmergować lub odwrócić niezależnie.

## Current State Analysis

- **K3:** `FirestoreRestoreUseCase.restoreIfEmpty()` chronione `restoreIfEmptyMutex.withLock` (linia 49), ale `restoreFromFirestore()` (linie 64–78) wywołuje identyczne serwisy Firestore bez ochrony. Mutex był dodany celowo (`3c1d674`) wyłącznie dla scenariusza dwa razy `restoreIfEmpty` — scenariusz `restoreIfEmpty` + `restoreFromFirestore` równolegle (startup + user sync button) nie był rozważany.
- **K2:** `CapPositionFirestoreService.fetchAll()` (linie 65–84) zawiera ~20 linii inline: guard na `capImageUrl`, konstruktor `CapSnapshot` z 6 polami i budowa `CapPositionDocument`. Żadna klasa mapper nie istnieje; pozostałe dwa serwisy Firestore mają mapping trywialny (2–3 pola) i mogą pozostać inline.
- **K5:** `AuthRepository.fetchApiToken()` (linie 78–97) to prywatna metoda sieciowa wewnątrz klasy, która zarządza Sanctum session. Logika ta wywołuje `authApiService.apiToken()`, parsuje odpowiedź i woła `sessionRepository.setToken()` — naturalne miejsce dla tych odpowiedzialności to `SessionRepository`, który już posiada `setToken()` i `loadCachedToken()`. `FirebaseAuthManager.signInWithEmail` już istnieje i `AuthRepository.kt:56` już deleguje do niego — ten krok K5 jest już wykonany.

### Key Discoveries:

- `SessionRepository` ma tylko `@ApplicationContext context: Context` w konstruktorze (43 linie, zero sieci). Dodanie `authApiService: AuthApiService` jako drugiego param to addytywna zmiana bez żadnego wpływu na DI modules — Hilt wykrywa automatycznie przez `@Inject constructor`.
- `AuthApiService` zostaje w `AuthRepository` po K5 (nadal potrzebny dla `initCsrf()`, `login()`, `logout()`).
- `AuthRepository.init` block (linie 28–36) korzysta z `sessionRepository.setToken(cached)` niezależnie od `fetchApiToken()` — ten kod nie jest dotknięty w K5.
- `AuthRepositoryTest.kt` ma 6 testów (nie 9 jak raport podał): 3x init, login success, login 422, logout. Tylko test login-success (linia 106) wymaga aktualizacji weryfikacji po K5.

## Desired End State

### K3
`restoreFromFirestore()` i `restoreIfEmpty()` są chronione tym samym mutex. Uruchomienie obu metod równolegle (startup + user sync button) serializuje dostęp do Firestore i Room — brak race condition cross-method.

### K2
`CapPositionFirestoreService.fetchAll()` deleguje budowę `CapPositionDocument` do `QueryDocumentSnapshot.toCapPositionDocument()` z `CapPositionMapper.kt`. Guard nullability `capImageUrl` jest izolowany i widoczny w osobnym pliku.

### K5
`SessionRepository` zawiera `suspend fun fetchAndStoreApiToken(email, password)` i ma `authApiService` wstrzyknięte. `AuthRepository` nie ma już prywatnej metody sieciowej — deleguje do `sessionRepository.fetchAndStoreApiToken()`.

**Weryfikacja end state:**
- `grep -n "withLock" FirestoreRestoreUseCase.kt` zwraca 2 wyniki (po jednym na metodę)
- `CapPositionFirestoreService.fetchAll()` ma ≤ 5 linii ciała
- `AuthRepository.kt` nie zawiera `fetchApiToken` jako prywatnej metody
- `SessionRepository.kt` zawiera `fetchAndStoreApiToken`
- `./gradlew testDebugUnitTest ktlintCheck` przechodzi

## What We're NOT Doing

- **Sanctum init block** nie jest ruszany — pełna dekompozycja AuthRepository (cookie/CSRF → osobna klasa) to oddzielna decyzja planistyczna (open question z research.md)
- **K1** (interfejs dla FirestoreRestoreUseCase) — niskie ROI, mockk działa na klasach
- **K4** (koordynator dual-callsite) — świadome ograniczenie, Mutex obsługuje poprawnie
- **K6** (CapExtended fan-out) — pytanie domenowe, nie strukturalne
- **K7** (CapsRepository hub) — idiom Paging3, nie problem
- **K8/K9/K10** — świadome ograniczenia udokumentowane w CLAUDE.md lub niski priorytet
- Testy instrumentowane **nie trafią do CI** w tym planie — to osobny zakres

## Implementation Approach

Fazy są niezależne i mogą być commitowane osobno. Kolejność: K3 (najmniejsze ryzyko, zamknięcie race condition) → K2 (addytywna ekstrakcja, zero ryzyka behawioralnego) → K5 (przeniesienie logiki, wymaga aktualizacji testu).

---

## Phase 1: K3 — Symetryczny Mutex

### Overview

Opakuj ciało `restoreFromFirestore()` w `restoreIfEmptyMutex.withLock { }`. Dodaj test mieszanego wyścigu do istniejącego suite androidTest.

### Changes Required:

#### 1. Mutex w restoreFromFirestore()

**File**: `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt`

**Intent**: Ochronić `restoreFromFirestore()` tym samym mutex co `restoreIfEmpty()`, żeby startup-triggered restore i user-triggered sync nie mogły biec równolegle przez Firestore i Room.

**Contract**: `restoreIfEmptyMutex.withLock { }` opakowuje całe ciało po bloku sprawdzenia UID — tak samo jak linia 49 w `restoreIfEmpty()`. Sygnatura i zwracany typ metody bez zmian.

#### 2. Test mieszanego wyścigu

**File**: `app/src/androidTest/java/pl/sroki/cci/android/data/FirestoreRestoreUseCaseTest.kt`

**Intent**: Udokumentować i zabezpieczyć nowy scenariusz: `restoreIfEmpty` i `restoreFromFirestore` uruchomione równolegle nie powodują duplikatów ani niespójności danych.

**Contract**: Nowa metoda `@Test fun restoreIfEmpty_and_restoreFromFirestore_concurrent_noDuplicates()`. Wzorzec `runBlocking` z `launch` dla obu metod — identycznie jak istniejący test `restoreIfEmpty_concurrentCalls_noDuplicates` (linia 111). Test uruchamia obie metody z nakładem czasowym i sprawdza że Room nie zawiera duplikatów.

### Success Criteria:

#### Automated Verification:

- `./gradlew testDebugUnitTest` przechodzi
- `./gradlew ktlintCheck` przechodzi

#### Manual Verification:

- Uruchom androidTest lokalnie na emulatorze: `./gradlew connectedDebugAndroidTest --tests "*.FirestoreRestoreUseCaseTest"` — wszystkie testy (stary + nowy) przechodzą
- Sprawdź `grep -n "withLock" app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt` — 2 wyniki (linie ~49 i ~68)

**Implementation Note**: Po przejściu automated CI i ręcznym androidTest — zatrzymaj się i poczekaj na potwierdzenie przed fazą 2.

---

## Phase 2: K2 — CapPositionMapper

### Overview

Ekstraktuj inline mapping z `CapPositionFirestoreService.fetchAll()` do nowej extension function `QueryDocumentSnapshot.toCapPositionDocument()` w dedykowanym pliku.

### Changes Required:

#### 1. Nowy plik CapPositionMapper.kt

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/CapPositionMapper.kt`

**Intent**: Przenieść logikę budowania `CapPositionDocument` (guard nullability `capImageUrl`, konstruktor `CapSnapshot` z 6 polami) do izolowanego, nazwanego miejsca — czyniąc invariant `capImageUrl == null → snapshot null` widocznym.

**Contract**: Package `pl.sroki.cci.android.data.datasource.remote.firestore`. Jedna funkcja: `fun QueryDocumentSnapshot.toCapPositionDocument(): CapPositionDocument?`. Zwraca `null` jeśli dokument jest malformed (zachowanie identyczne z obecnym `?.let`). Importy: `CapPositionDocument`, `CapSnapshot` z `model/`. Zachowuje dokładnie te same nazwy pól Firestore i wartości domyślne co linie 65–84 oryginału.

#### 2. Zastąpienie inline logic w CapPositionFirestoreService

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/CapPositionFirestoreService.kt`

**Intent**: Zastąpić 20 linii inline mapping jednym wywołaniem `doc.toCapPositionDocument()`.

**Contract**: Ciało `fetchAll()` skraca się do `snapshot.documents.mapNotNull { doc -> doc.toCapPositionDocument() }` (lub analogicznie — `mapNotNull` już obsługuje `null`). Import nowej extension function.

### Success Criteria:

#### Automated Verification:

- `./gradlew testDebugUnitTest` przechodzi
- `./gradlew ktlintCheck` przechodzi

#### Manual Verification:

- Uruchom androidTest lokalnie: `./gradlew connectedDebugAndroidTest --tests "*.FirestoreRestoreUseCaseTest"` — wszystkie testy przechodzą (mapper wywołany przez serwis mockowany przez UseCase test)
- Sprawdź `wc -l CapPositionFirestoreService.kt` — plik skrócił się o ~15 linii

**Implementation Note**: Po przejściu automated CI i ręcznym androidTest — zatrzymaj się i poczekaj na potwierdzenie przed fazą 3.

---

## Phase 3: K5 — fetchApiToken() → SessionRepository

### Overview

Przenieść `fetchApiToken()` z `AuthRepository` do `SessionRepository` jako `suspend fun fetchAndStoreApiToken()`. `SessionRepository` otrzymuje `authApiService` jako drugi param konstruktora. Zaktualizować test.

### Changes Required:

#### 1. Dodaj fetchAndStoreApiToken do SessionRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/SessionRepository.kt`

**Intent**: Przenieść logikę pobierania Bearer tokenu z prywatnej metody `AuthRepository` do klasy, która już zarządza tokenem — eliminując sieć w `AuthRepository` dla tej odpowiedzialności.

**Contract**: Konstruktor rozszerzony o `private val authApiService: AuthApiService` (drugi param po `@ApplicationContext context: Context`). Hilt wstrzykuje automatycznie — brak zmian w `NetworkModule` ani innych modułach DI. Nowa metoda: `suspend fun fetchAndStoreApiToken(email: String, password: String)` — skopiowane ciało `AuthRepository.fetchApiToken` (linie 78–97) bez żadnych zmian semantycznych. Potrzebny `companion object { private val json = Json { ignoreUnknownKeys = true } }` i importy: `AuthApiService`, `TokenRequest`, `LoginResponse`, `Log`, `kotlinx.serialization.json.Json`.

#### 2. Usuń fetchApiToken z AuthRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt`

**Intent**: Usunąć prywatną metodę sieciową, delegując do `sessionRepository.fetchAndStoreApiToken()`.

**Contract**: Usuń linie 78–97 (cały `private suspend fun fetchApiToken`). Zmień `runCatching { fetchApiToken(email, password) }` (linia 51) na `runCatching { sessionRepository.fetchAndStoreApiToken(email, password) }`. Usuń importy `TokenRequest`, `LoginResponse` jeśli nie są używane nigdzie indziej w pliku. `authApiService` zostaje (nadal potrzebny dla Sanctum).

#### 3. Zaktualizuj AuthRepositoryTest

**File**: `app/src/test/java/pl/sroki/cci/android/data/AuthRepositoryTest.kt`

**Intent**: Zaktualizować weryfikację dla ścieżki Bearer token w teście login-success — `setToken` było wywoływane przez prywatną metodę (niewidoczną dla mocka); teraz `fetchAndStoreApiToken` jest wywoływane bezpośrednio na mocku `sessionRepository`.

**Contract**: W teście `login success` (linia 106): zamień weryfikację `verify { sessionRepository.setToken(any()) }` (jeśli istnieje) na `verify { sessionRepository.fetchAndStoreApiToken(email, password) }`. `sessionRepository` jest `mockk(relaxed = true)` — nie potrzeba `every { }` setup dla nowej metody. Pozostałe 5 testów nie jest dotknięte (init block i logout nie używają `fetchApiToken`).

### Success Criteria:

#### Automated Verification:

- `./gradlew testDebugUnitTest` przechodzi (6 testów AuthRepositoryTest)
- `./gradlew ktlintCheck` przechodzi
- `grep -rn "fetchApiToken" app/src/` — 0 wyników (metoda usunięta ze wszystkich plików)
- `grep -n "fetchAndStoreApiToken" app/src/main/java/pl/sroki/cci/android/data/SessionRepository.kt` — 1 wynik

#### Manual Verification:

- Uruchom aplikację na emulatorze, przejdź przez flow logowania: email + hasło → sukces → aplikacja wyświetla dane kolekcji
- Sprawdź Logcat: `CCI_AUTH` logi pokazują `api token fetched: true` lub `api token: reusing cached`

**Implementation Note**: Po przejściu wszystkich 6 testów CI i manualnej weryfikacji logowania — zatrzymaj się i poczekaj na potwierdzenie przed zmergowaniem PR.

---

## Testing Strategy

### Unit Tests:

- `AuthRepositoryTest.kt` (6 testów) — chroni K5; po K5 jeden test (login-success) wymaga aktualizacji weryfikacji
- CI: `./gradlew testDebugUnitTest` — pokrywa AuthRepository, CapsRepository, inne

### Integration Tests (poza CI):

- `FirestoreRestoreUseCaseTest.kt` (androidTest) — chroni K3 (concurrent) i K2 (mapper wywołany przez serwisy mockowane przez UseCase)
- Uruchamiać ręcznie po fazach 1 i 2: `./gradlew connectedDebugAndroidTest --tests "*.FirestoreRestoreUseCaseTest"`

### Manual Testing Steps:

1. **K3:** Brak prostego manualnego testu race condition — polegamy na nowym teście instrumentowanym
2. **K2:** Otwórz widok kolekcji, sprawdź że CapPosition z i bez capImageUrl wyświetlają się poprawnie (snapshot ładuje lub jest null)
3. **K5:** Wyloguj → zaloguj → sprawdź w Logcat że token jest fetchowany (`CCI_AUTH: api token fetched`)

## References

- Research (zweryfikowany): `context/changes/refactor-opportunities/research.md`
- Prior research (dług techniczny): `context/changes/firestore-restore-flow/research.md`
- `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt` — K3 target
- `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/CapPositionFirestoreService.kt:65–84` — K2 target
- `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt:78–97` — K5 source
- `app/src/main/java/pl/sroki/cci/android/data/SessionRepository.kt` — K5 destination
- `app/src/androidTest/java/pl/sroki/cci/android/data/FirestoreRestoreUseCaseTest.kt` — K3/K2 androidTest

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: K3 — Symetryczny Mutex

#### Automated

- [x] 1.1 `./gradlew testDebugUnitTest` przechodzi — eb76be8
- [x] 1.2 `./gradlew ktlintCheck` przechodzi — eb76be8

#### Manual

- [x] 1.3 androidTest `FirestoreRestoreUseCaseTest` — wszystkie testy (stary + nowy) przechodzą lokalnie — eb76be8
- [x] 1.4 `grep -n "withLock" FirestoreRestoreUseCase.kt` zwraca 2 wyniki — eb76be8

### Phase 2: K2 — CapPositionMapper

#### Automated

- [x] 2.1 `./gradlew testDebugUnitTest` przechodzi — 42a9b1b
- [x] 2.2 `./gradlew ktlintCheck` przechodzi — 42a9b1b

#### Manual

- [x] 2.3 androidTest `FirestoreRestoreUseCaseTest` — wszystkie testy przechodzą lokalnie — 42a9b1b
- [x] 2.4 `CapPositionFirestoreService.fetchAll()` ma ≤ 5 linii ciała — 42a9b1b

### Phase 3: K5 — fetchApiToken() → SessionRepository

#### Automated

- [x] 3.1 `./gradlew testDebugUnitTest` przechodzi (6 testów AuthRepositoryTest) — c69184f
- [x] 3.2 `./gradlew ktlintCheck` przechodzi — c69184f
- [x] 3.3 `grep -rn "fetchApiToken" app/src/` — 0 wyników — c69184f
- [x] 3.4 `grep -n "fetchAndStoreApiToken" SessionRepository.kt` — 1 wynik — c69184f

#### Manual

- [x] 3.5 Manualny flow logowania na emulatorze — sukces + Logcat `CCI_AUTH: api token fetched` — c69184f

---
change_id: test-collection-verifier-and-auth
created: 2026-06-15
updated: 2026-06-15
status: planned
---

# test-collection-verifier-and-auth — Plan implementacji

## Overview

Dodajemy testy jednostkowe JVM (Phase A z `context/foundation/test-plan.md`) pokrywające trzy
ryzyka bez żadnego testu: R1 (CollectionVerifier — błędny CatalogStatus), R3
(FirebaseAuthManager — fallback createUser), R6 (stale Bearer token → isLoggedIn=true).
Wynik: 3 nowe pliki testowe + 2 testy dopisane do `AuthRepositoryTest`.

## Current State Analysis

- `CollectionVerifier` — 5 zależności, logika `verify()` obsługuje 5 ścieżek; `runBatch`
  używa `Semaphore(4)` i `delay(120ms)`. Brak jakiegokolwiek testu.
- `FirebaseAuthManager.signInWithEmail` — `catch (_: Exception) { createUser }` łapie każdy
  wyjątek, w tym złe hasło — prowadzi do tworzenia duplikatu konta Firebase (R3 bug).
- `AuthRepository.init` — `sessionRepository.setLoggedIn(cachedToken != null)` bez weryfikacji
  ważności tokena (R6); istniejące testy `AuthRepositoryTest` pokrywają tylko `cookie=true/false`,
  nie przypadek `cachedToken != null` z brakiem cookie.
- `SessionAuthenticator` — `authenticate()` czyści sesję przy 401, brak testu; klasa
  wstrzykuje `CookieJar` i `SessionRepository` przez konstruktor.

## Desired End State

Po zakończeniu planu:
- `CollectionVerifierTest` pokrywa wszystkie 5 statusów `verify()` + isCancelled + Semaphore.
- `FirebaseAuthManagerTest` udowadnia, że błąd hasła nie powoduje `createUserWithEmailAndPassword`.
- `SessionAuthenticatorTest` weryfikuje, że 401 czyści cookies i ustawia `isLoggedIn=false`.
- `AuthRepositoryTest` ma nowy test: init z tokenem bez cookie → `isLoggedIn=true` (R6 regresja).
- `./gradlew testDebugUnitTest` przechodzi bez błędów.

### Key Discoveries

- `CapExtended.toSnapshot()` mapuje `description`, `country.name`, `imageUrl`, `createdAt.toString()`,
  `createdBy?.id`, `updatedAt?.toString()` — fingerprint to `createdAt + createdById`.
- `CapCache.createdAt == null` = baseline (nowy wpis); zapis snapshotu + `pushSnapshot()` bez alarmu.
- `FirebaseAuthManager.uid` to `StateFlow<String?>`; w testach `CollectionVerifier` — uid = null
  powoduje wczesne zakończenie `pushSnapshot()` bez Firestore call (przydatne do izolacji).
- `SessionAuthenticator` zwraca `null` z `authenticate()` (bez retry) — OkHttp wylogowuje sesję.
- `delay(120ms)` w `runBatch` — pominąć przez `StandardTestDispatcher` i `advanceUntilIdle()`.

## What We're NOT Doing

- Testy instrumentowane (Room/Firestore) — to Phase B i C z test-planu.
- Testy `CollectionVerificationViewModel` (logika UI, §5 test-planu — brak wartości).
- Testy `onProgress` callback w `runFullScan` (brak logiki biznesowej).
- Rozszerzanie CI o emulator — kandydat do osobnej zmiany.

## Implementation Approach

Wszystkie trzy fazy dodają pliki testowe w `app/src/test/`. Każda klasa używa mockk dla
zależności wstrzykiwanych przez konstruktor. Dispatcher: `StandardTestDispatcher` przekazywany
do `runTest { }` — `advanceUntilIdle()` skipuje `delay()`. Dla testu Semaphore:
`AtomicInteger` śledzi aktywne wywołania; po `advanceUntilIdle()` sprawdzamy maksimum.

---

## Phase 1: CollectionVerifierTest

### Overview

Nowy plik testowy dla `CollectionVerifier`. Pokrywa `verify()` (5 ścieżek), `runBatch`
z `isCancelled()` i walidację limitu Semaphore(4).

### Changes Required

#### 1. Nowy plik — CollectionVerifierTest

**File**: `app/src/test/java/pl/sroki/cci/android/data/CollectionVerifierTest.kt`

**Intent**: Stworzyć klasę testową z setup mocków i zestawem testów pokrywających R1.

**Contract**:
- Pakiet: `pl.sroki.cci.android.data`
- Zależności mockk: `CapsRepository`, `CapCacheRepository`, `CapPositionRepository`,
  `CapPositionFirestoreService`, `FirebaseAuthManager`
- Helper prywatny `capExtended(...)` konstruuje `CapExtended` z rozsądnymi defaultami;
  parametry opcjonalne: `createdAt: Instant`, `createdById: Int?`, `updatedAt: Instant?`
- Helper prywatny `capCache(capId: Long, ...)` konstruuje `CapCache` z defaultami
- `FirebaseAuthManager.uid` mockowany jako `MutableStateFlow<String?>(null)` — `pushSnapshot`
  kończy działanie wcześnie, co izoluje testy od Firestore
- `@Before` konfiguruje `coEvery { capCacheRepository.markVerified(...) } just Runs`
  i `coEvery { capCacheRepository.upsertSnapshot(...) } just Runs`

**Testy do zaimplementowania**:
- `verify — brak snapshotu (baseline) — zwraca OK i zapisuje snapshot`: `getOne` zwraca null;
  oczekuj `CatalogStatus.OK`; weryfikuj `upsertSnapshot` wywołane
- `verify — fingerprint zgodny — zwraca OK`: `stored.createdAt == fresh.createdAt`,
  `stored.createdById == fresh.createdById`, `stored.updatedAt == fresh.updatedAt` itp.
- `verify — zmiana updatedAt — zwraca UPDATED`: `stored.updatedAt != fresh.updatedAt`;
  oczekuj `CatalogStatus.UPDATED`; `upsertSnapshot` NIE wywołane
- `verify — zmiana createdAt — zwraca SWAPPED`: `stored.createdAt != fresh.createdAt`;
  oczekuj `CatalogStatus.SWAPPED`
- `verify — 404 HTTP — zwraca MISSING`: `capsRepository.getById` rzuca `HttpException`
  z kodem 404; oczekuj `CatalogStatus.MISSING`; `markVerified(capId, MISSING, ...)` wywołane
- `runFullScan — isCancelled=true po pierwszym elemencie — nie przetwarza kolejnych`:
  `getAllCapIds()` zwraca 5 ids; `isCancelled` zwraca `false` raz, potem `true`;
  `markVerified` wywołane co najwyżej 1 raz (async + Semaphore mogą wpuścić więcej; weryfikuj ≤ Semaphore size)
- `runBatch — max 4 równoległe wywołania verify`: `getAllCapIds()` zwraca 8 ids;
  `AtomicInteger activeCalls` inkrementowany przy wejściu do `capsRepository.getById`,
  dekrementowany przy wyjściu; po `advanceUntilIdle()` max zaobserwowany ≤ 4

### Success Criteria

#### Automated Verification

- `./gradlew testDebugUnitTest` przechodzi bez błędów
- Wszystkie 7 testów w `CollectionVerifierTest` zielone
- Brak nowych lint warnings

#### Manual Verification

- Uruchom testy lokalnie w Android Studio i potwierdź, że przechodzą (Run > CollectionVerifierTest)

**Implementation Note**: Po zakończeniu fazy i przejściu automatycznej weryfikacji, poczekaj
na ręczne potwierdzenie przed przejściem do Fazy 2.

---

## Phase 2: FirebaseAuthManagerTest

### Overview

Nowy plik testowy pokrywający R3 — bug `signInWithEmail` z fallbackiem do `createUser`.

### Changes Required

#### 1. Nowy plik — FirebaseAuthManagerTest

**File**: `app/src/test/java/pl/sroki/cci/android/data/FirebaseAuthManagerTest.kt`

**Intent**: Udowodnić, że błąd hasła i błąd sieci NIE wywołują `createUserWithEmailAndPassword`.

**Contract**:
- Pakiet: `pl.sroki.cci.android.data`
- `FirebaseAuth` mockowany przez `mockk<FirebaseAuth>()` (constructor injection)
- `auth.currentUser` mockowany jako null (brak aktywnej sesji) we wszystkich testach
- Mockowanie `auth.signInWithEmailAndPassword(...)`: zwraca `mockk<Task<AuthResult>>` z wynikiem
  lub rzuca `FirebaseAuthInvalidCredentialsException` / `IOException` w zależności od testu
- Uwaga: `Task.await()` wymaga `kotlinx-coroutines-play-services` i działa z `runTest`;
  alternatywnie spy lub `every { task.await() }` przez mockk coroutines extension

**Testy do zaimplementowania**:
- `signInWithEmail sukces — uid uaktualniony`: `signIn` zwraca sukces; `auth.currentUser?.uid`
  zwraca `"uid-123"`; po wywołaniu `manager.uid.value == "uid-123"`
- `signInWithEmail złe hasło — NIE wywołuje createUserWithEmailAndPassword`:
  `signIn` rzuca `FirebaseAuthInvalidCredentialsException`; weryfikuj `coVerify(exactly=0) { auth.createUserWithEmailAndPassword(any(), any()) }`
- `signInWithEmail błąd sieci — rzuca wyjątek, NIE tworzy konta`: `signIn` rzuca `IOException`;
  test oczekuje wyjątku (lub `runCatching`); weryfikuj `coVerify(exactly=0) { auth.createUserWithEmailAndPassword(...) }`

**Uwaga implementacyjna dotycząca R3 bug**: aktualny kod łapie `Exception` co obejmuje oba
przypadki — `FirebaseAuthInvalidCredentialsException` i `IOException`. Testy te prawdopodobnie
FAIL-ną na bieżącej implementacji dla przypadków "złe hasło" i "błąd sieci", ponieważ
`createUser` zostanie wywołane. To jest zamierzone — testy dokumentują regresję.

### Success Criteria

#### Automated Verification

- `./gradlew testDebugUnitTest` przechodzi bez błędów
- Testy sukcesu zielone; testy błędów (złe hasło, sieć) wskazują bug przez FAIL lub są
  zamarkowane `@Ignore("R3 bug — createUser wywołane przy każdym wyjątku")` jeśli decydujesz
  się najpierw dokumentować bez naprawy

#### Manual Verification

- Potwierdź w Android Studio które testy failują (dokumentują R3 bug) vs przechodzą

**Implementation Note**: Testy R3 celowo mogą failować na bieżącym kodzie — to sygnał bugu,
nie problemu z testem. Decyzja o naprawie bugu należy do osobnej zmiany lub w tej fazie
jeśli zdecydujesz się naprawić `signInWithEmail`. Poczekaj na ręczne potwierdzenie.

---

## Phase 3: SessionAuthenticatorTest + AuthRepositoryTest patch

### Overview

Nowy `SessionAuthenticatorTest` weryfikuje reakcję na 401. Dwa dodatkowe testy w istniejącym
`AuthRepositoryTest` pokrywają R6.

### Changes Required

#### 1. Nowy plik — SessionAuthenticatorTest

**File**: `app/src/test/java/pl/sroki/cci/android/data/datasource/remote/auth/SessionAuthenticatorTest.kt`

**Intent**: Udowodnić, że `SessionAuthenticator.authenticate()` czyści cookies i wylogowuje
użytkownika przy odpowiedzi 401.

**Contract**:
- Pakiet: `pl.sroki.cci.android.data.datasource.remote.auth`
- Zależności: `SessionRepository` (używa mocka Context jak w `AuthRepositoryTest.mockContext()`),
  `PersistentCookieJar` przez `mockk(relaxed=true)`
- `authenticate()` przyjmuje `route: Route?` (null) i `response: Response` — konstruktor
  OkHttp Response: `Response.Builder().code(401).request(...).protocol(...).message("Unauthorized").build()`

**Testy do zaimplementowania**:
- `authenticate — 401 — czyści cookies i ustawia isLoggedIn=false`:
  `sessionRepository.setLoggedIn(true)` przed wywołaniem; po `authenticate()` weryfikuj
  `sessionRepository.isLoggedIn.value == false` i `verify { cookieJar.clear() }`;
  wynik `authenticate()` to `null` (brak retry)
- `authenticate — zwraca null (brak retry)`: weryfikuj return value == null

#### 2. Patch — AuthRepositoryTest (dwa nowe testy)

**File**: `app/src/test/java/pl/sroki/cci/android/data/AuthRepositoryTest.kt`

**Intent**: Uzupełnić lukę R6 — udokumentować, że init z cachedToken bez cookie ustawia
`isLoggedIn=true` (oczekiwane zachowanie, ale do świadomego zaakceptowania).

**Contract**: Dopisać dwa testy w istniejącej klasie `AuthRepositoryTest`:
- `init — brak cookie, jest token — isLoggedIn true`: `cookieJar.loadForRequest` zwraca
  `emptyList()`; `SessionRepository` skonfigurowany z SharedPreferences zwracającym
  `"bearer-token"` dla klucza `"api_token"`; po `buildRepo()` weryfikuj
  `sessionRepository.isLoggedIn.value == true`
- `init — brak cookie, brak tokenu — isLoggedIn false`: istniejący test; dopisz komentarz
  `// R6: wyjście negatywne — brak tokenu = wylogowany`

Uwaga: `mockContext()` aktualnie zwraca `null` dla `api_token`; nowy test potrzebuje wariantu
`mockContextWithToken(token: String)` zwracającego token.

### Success Criteria

#### Automated Verification

- `./gradlew testDebugUnitTest` przechodzi bez błędów
- Nowe testy w `SessionAuthenticatorTest` zielone
- Nowe testy w `AuthRepositoryTest` zielone

#### Manual Verification

- Potwierdź w Android Studio że wszystkie nowe testy przechodzą

---

## Testing Strategy

### Unit Tests (JVM)

- Język: Kotlin 2.1.20; `io.mockk:mockk`; `kotlinx-coroutines-test` (`runTest`, `StandardTestDispatcher`)
- Runner: `./gradlew testDebugUnitTest` (istniejący CI step)
- Każdy test: własny `@Before setUp()`, brak shared state między testami

### Integration Tests

- Brak w tej fazie (Phase B i C z test-planu).

### Manual Testing Steps

1. Otwórz Android Studio → Run > `CollectionVerifierTest` — zweryfikuj 7 zielonych testów
2. Otwórz Android Studio → Run > `FirebaseAuthManagerTest` — zidentyfikuj które testy failują (dokumentują R3)
3. Otwórz Android Studio → Run > `SessionAuthenticatorTest` — zweryfikuj 2 zielone testy
4. Otwórz Android Studio → Run > `AuthRepositoryTest` — upewnij się, że nowe testy przechodzą i stare nie zregresowały

## Performance Considerations

Testy JVM bez opóźnień sieciowych; `StandardTestDispatcher` skipuje `delay(120ms)` — cały
`CollectionVerifierTest` powinien zakończyć się w < 500ms.

## References

- Test Plan: `context/foundation/test-plan.md` — Phase A (§3)
- CollectionVerifier: `app/src/main/java/pl/sroki/cci/android/data/CollectionVerifier.kt`
- FirebaseAuthManager: `app/src/main/java/pl/sroki/cci/android/data/FirebaseAuthManager.kt`
- SessionAuthenticator: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/auth/SessionAuthenticator.kt`
- AuthRepository: `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt`
- Istniejące testy: `app/src/test/java/pl/sroki/cci/android/data/AuthRepositoryTest.kt`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: CollectionVerifierTest

#### Automated

- [x] 1.1 `./gradlew testDebugUnitTest` przechodzi bez błędów
- [x] 1.2 Wszystkie 7 testów w `CollectionVerifierTest` zielone
- [x] 1.3 Brak nowych lint warnings

#### Manual

- [x] 1.4 Uruchom CollectionVerifierTest w Android Studio i potwierdź przejście

### Phase 2: FirebaseAuthManagerTest

#### Automated

- [ ] 2.1 `./gradlew testDebugUnitTest` przechodzi bez błędów
- [ ] 2.2 Test sukcesu (`signInWithEmail sukces`) zielony
- [ ] 2.3 Testy R3 bug (złe hasło, błąd sieci) failują lub są @Ignore z komentarzem

#### Manual

- [ ] 2.4 Zidentyfikuj w Android Studio które testy dokumentują R3 bug

### Phase 3: SessionAuthenticatorTest + AuthRepositoryTest patch

#### Automated

- [ ] 3.1 `./gradlew testDebugUnitTest` przechodzi bez błędów
- [ ] 3.2 Nowe testy w `SessionAuthenticatorTest` zielone
- [ ] 3.3 Nowe testy w `AuthRepositoryTest` zielone (init + token)

#### Manual

- [ ] 3.4 Potwierdź w Android Studio brak regresji w istniejącym `AuthRepositoryTest`

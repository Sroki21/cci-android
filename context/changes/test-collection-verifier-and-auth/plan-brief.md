# test-collection-verifier-and-auth — Plan Brief

> Full plan: `context/changes/test-collection-verifier-and-auth/plan.md`
> Test Plan: `context/foundation/test-plan.md` — Phase A

## What & Why

Dodajemy testy jednostkowe JVM pokrywające Phase A z test-planu: R1 (CollectionVerifier —
brak testów dla żadnego z 5 statusów CatalogStatus), R3 (FirebaseAuthManager — fallback
`createUser` na każdym wyjątku, w tym złe hasło), R6 (stale Bearer token daje `isLoggedIn=true`
bez weryfikacji). Właściciel wskazał te obszary jako „trzy obszary szczególnej troski" — testy
dają sygnał zanim bug trafi do produkcji.

## Starting Point

`AuthRepositoryTest` istnieje i pokrywa init (cookie), login 200/422, logout. CollectionVerifier,
FirebaseAuthManager i SessionAuthenticator nie mają żadnych testów. R3 bug jest już w kodzie
(`signInWithEmail` łapie `Exception` i wywołuje `createUser`).

## Desired End State

Trzy nowe pliki testowe + 2 dodatkowe testy w `AuthRepositoryTest`. `./gradlew testDebugUnitTest`
przechodzi. Testy R3 failują (dokumentując istniejący bug) lub są `@Ignore` z komentarzem
— intencjonalne, bo naprawa bugu to osobna decyzja.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Firebase mocking | mockk() | Constructor injection umożliwia mock bez Firebase SDK | Plan |
| delay(120ms) w testach | StandardTestDispatcher + advanceUntilIdle | Virtual time skipuje throttle | Plan |
| CapExtended build | helper capExtended() w pliku testowym | ~25 pól + zagnieżdżone obiekty — helper > inline | Plan |
| SessionAuthenticator scope | nowy SessionAuthenticatorTest.kt | Jeden plik per klasa, spójne z projektem | Plan |
| Semaphore weryfikacja | AtomicInteger śledzi max concurrent | Bezpośrednio weryfikuje R1 risk | Plan |
| onProgress callback | nie testować | Brak logiki biznesowej, §5 test-planu | Plan |
| R6 dokumentacja | nowy test init z cachedToken | Dokumentuje ryzyko jako świadomą regresję | Plan |
| Zakres R3 | 3 przypadki: sukces + złe hasło + sieć | Pełne pokrycie R3 zgodne z test-planem | Plan |

## Scope

**In scope:**
- `CollectionVerifierTest` — 7 testów (5 ścieżek verify + isCancelled + Semaphore)
- `FirebaseAuthManagerTest` — 3 testy (sukces, złe hasło, błąd sieci)
- `SessionAuthenticatorTest` — 2 testy (authenticate clears + returns null)
- `AuthRepositoryTest` patch — 1 nowy test (init z tokenem bez cookie)

**Out of scope:**
- Naprawa R3 bug (oddzielna zmiana jeśli właściciel zdecyduje)
- Phase B (migracje Room) i Phase C (Firestore/slots) — osobne change-IDs
- Testy instrumentowane (emulator/device)
- Rozszerzenie CI o emulator

## Architecture / Approach

Każda klasa testowa mockuje zależności przez constructor injection (`mockk()`). Dispatcher:
`StandardTestDispatcher` przekazywany do `runTest {}` — `advanceUntilIdle()` skipuje
`delay(120ms)` w CollectionVerifier. Testy R3 celowo failują na bieżącym kodzie (sygnał bugu).

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. CollectionVerifierTest | 7 testów verify() + Semaphore + isCancelled | CapExtended z Instant — helper może być trudny |
| 2. FirebaseAuthManagerTest | Dokumentuje R3 bug; 1 test zielony, 2 failują | Task.await() z mockk może wymagać coroutines-play-services stub |
| 3. SessionAuthenticatorTest + patch | R6 udokumentowany; SessionAuthenticator przetestowany | Konstruktor OkHttp Response — verbose builder |

**Prerequisites:** Żadne — Phase A to testy JVM, nie wymagają emulator/device.
**Estimated effort:** ~1 sesja (2-3h), 3 pliki + 1 patch.

## Open Risks & Assumptions

- `Task.await()` (Firebase) w testach JVM może wymagać `mockk-android` lub stub — weryfikacja
  podczas implementacji Phase 2.
- Testy R3 failują intencjonalnie — decyzja o naprawie bugu spada na właściciela po zobaczeniu
  wyników.
- `CapExtended` ma ~25 pól z zagnieżdżonymi obiektami (Country, Product itp.) — helper może
  być verbose; alternatywa to `relaxed=true` mockk dla przypadków bez fingerprint comparison.

## Success Criteria (Summary)

- `./gradlew testDebugUnitTest` przechodzi bez błędów
- R1: CollectionVerifierTest — 7 zielonych testów, wszystkie 5 statusów CatalogStatus pokryte
- R3: FirebaseAuthManagerTest — test sukcesu zielony; testy błędów failują (dokumentują bug)
- R6: SessionAuthenticatorTest + AuthRepositoryTest — init z tokenem i 401 oczyszczenie przetestowane

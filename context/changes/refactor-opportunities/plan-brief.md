# Refactor Opportunities — Plan Brief

> Full plan: `context/changes/refactor-opportunities/plan.md`
> Research: `context/changes/refactor-opportunities/research.md`

## What & Why

Trzy niezależne refaktory strukturalne zidentyfikowane przez analizę długu technicznego: zamknięcie latentnej race condition w mutex (K3), ekstrakcja nadmiarowej logiki mappera z serwisu Firestore (K2) i przeniesienie logiki pobierania Bearer tokenu do właściwego właściciela stanu (K5). Wszystkie trzy mają niski blast radius i są addytywne lub czysto przemieszczające — żadne nie zmienia zachowania zewnętrznego.

## Starting Point

- `FirestoreRestoreUseCase.restoreFromFirestore()` nie ma `withLock` mimo że `restoreIfEmpty()` jest chronione tym samym mutex od commitu `3c1d674`
- `CapPositionFirestoreService.fetchAll()` zawiera ~20 linii inline mapping z logiką warunkową snapshotu
- `AuthRepository.fetchApiToken()` to prywatna metoda sieciowa wewnątrz klasy zarządzającej Sanctum — `SessionRepository` (który już posiada `setToken`, `loadCachedToken`) jest właściwym właścicielem

## Desired End State

Obie metody `FirestoreRestoreUseCase` są chronione tym samym mutexem. `CapPositionFirestoreService.fetchAll()` ma ≤ 5 linii ciała, delegując do `CapPositionMapper`. `SessionRepository` zawiera `suspend fun fetchAndStoreApiToken()` i jest jedynym miejscem w kodzie, które pobiera i zapisuje Bearer token.

## Key Decisions Made

| Decyzja | Wybór | Dlaczego | Źródło |
|---------|-------|----------|--------|
| Zakres planu | K3 + K2 + K5 | Wszystkie trzy to przypadkowa złożoność z niskim blast radius; jeden PR, trzy commity | Plan |
| Test K3 | Extend androidTest (nie unit test) | Race condition wymaga rzeczywistego interleaving wątków; wzorzec `runBlocking` istnieje w projekcie | Plan |
| K2 — które serwisy | Tylko CapPositionFirestoreService | Binder (2 pola) i BinderPage (3 pola) są trywialne inline; mapper opłaca się przy ~12 liniach logiki | Research |
| K5 cel ekstrakcji | SessionRepository | Już posiada token state (`setToken`, `loadCachedToken`); naturalne miejsce na logikę pobierania | Plan |
| K5 zakres | Tylko fetchApiToken() | Firebase już delegowany (FirebaseAuthManager.kt:24); Sanctum init block wymaga decyzji o koordynatorze — oddzielna sesja | Research + Weryfikacja |
| Strategia commitów | Jeden PR, commit na fazę | Niezależne diffy w `git log`, jeden review | Plan |

## Scope

**In scope:**
- K3: `withLock` w `restoreFromFirestore()` + test mixed-concurrent (androidTest)
- K2: `CapPositionMapper.kt` (nowy) + refactor `CapPositionFirestoreService.fetchAll()`
- K5: `fetchAndStoreApiToken()` w `SessionRepository`, usunięcie `fetchApiToken()` z `AuthRepository`, aktualizacja 1 testu

**Out of scope:**
- Sanctum init block (`AuthRepository.kt:28–36`) — full decomposition AuthRepository to kolejna decyzja planistyczna
- Testy instrumentowane w CI — osobny zakres
- K1, K4, K6, K7, K8, K9, K10 — odrzucone lub odłożone

## Architecture / Approach

Trzy niezależne fazy wykonywane sekwencyjnie. Faza 1 (K3) i 2 (K2) są addytywne: żaden istniejący kontrakt się nie zmienia, testy instrumentowane weryfikują poprawność. Faza 3 (K5) przemieszcza logikę między klasami: `authApiService` zostaje w obu (`AuthRepository` nadal potrzebuje go dla Sanctum), `fetchAndStoreApiToken()` na mocku `sessionRepository` w testach — mockk relaxed obsługuje bez zmian `every {}`.

## Phases at a Glance

| Faza | Co dostarcza | Kluczowe ryzyko |
|------|-------------|----------------|
| 1. K3 — Mutex | `withLock` w `restoreFromFirestore()` + test concurrent mixed | Test androidTest poza CI; race condition niewidoczna w unit testach |
| 2. K2 — Mapper | `CapPositionMapper.kt` + czysty `fetchAll()` | Zachowanie identyczności null-safety guard `capImageUrl` |
| 3. K5 — Token | `fetchAndStoreApiToken()` w SessionRepository | Aktualizacja weryfikacji w 1 teście CI (login-success) |

**Prerequisites:** Zalogowany emulator dla androidTest (fazy 1–2). `authApiService.apiToken()` endpoint dostępny dla manualnego testu K5.
**Estimated effort:** ~1–2 sesje, 3 commity.

## Open Risks & Assumptions

- Faza 3: `authApiService` będzie wstrzyknięty w dwóch Singletonach (`AuthRepository` + `SessionRepository`). Jest `@Singleton` w Hilt — ta sama instancja, bez problemu; ale to nieoczywiste dla osoby czytającej kod.
- Faza 3: `AuthRepositoryTest.kt` linia 106 (login-success) wymaga aktualizacji weryfikacji. Zakładamy `mockk(relaxed = true)` — jeśli jest strict mock, potrzebny `every { sessionRepository.fetchAndStoreApiToken(any(), any()) } just Runs`.
- Pełna dekompozycja AuthRepository (K5 v2: Sanctum block) jest warunkowo zablokowana brakiem decyzji o koordynatorze `sessionRepository.isLoggedIn` — nie jest częścią tego planu.

## Success Criteria (Summary)

- `grep -n "withLock" FirestoreRestoreUseCase.kt` zwraca 2 linie (po jednym na metodę)
- `grep -rn "fetchApiToken" app/src/` — 0 wyników; `grep -n "fetchAndStoreApiToken" SessionRepository.kt` — 1 wynik
- `./gradlew testDebugUnitTest ktlintCheck` przechodzi na czystym branchu

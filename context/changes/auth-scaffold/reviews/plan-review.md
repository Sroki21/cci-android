<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Auth Scaffold — Plan implementacji

- **Plan**: `context/changes/auth-scaffold/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-11
- **Verdict**: REVISE → SOUND (po triage)
- **Findings**: 1 critical · 2 warnings · 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | WARNING (F1) |
| Lean Execution | OBSERVATION (F4) |
| Architectural Fitness | PASS |
| Blind Spots | WARNING (F2) |
| Plan Completeness | WARNING (F3) |

## Grounding

5/5 paths ✓ · 3/3 symbols ✓ · brief↔plan ✓

Paths verified: `settings.gradle` (JitPack absent — plan poprawnie identyfikuje do dodania),
`di/NetworkModule.kt:44` (istnieje, `provideRetrofitClient` bez `.client()`),
`data/datasource/remote/` (dir istnieje, `auth/` subdir do stworzenia),
`navigation/Screen.kt` (istnieje), `MainActivity.kt:55` (NavHost z `startDestination = Screen.Home.route`).

`docs/reference/contract-surfaces.md` — nie istnieje, check pominięty.

## Findings

### F1 — "lub 401" w End State nie ma implementacji

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — rzeczywisty wybór architektoniczny, ale fix jest czysty i wąski
- **Dimension**: End-State Alignment / Blind Spots
- **Location**: Desired End State #5 vs Phase 2 AuthRepository contract
- **Detail**: End State #5 obiecuje "lub 401 → logout" ale żaden mechanizm go nie budował. Istniejące ekrany nie zwracają 401 (anonimowe) więc problem nieobserwowalny do S-01.
- **Fix A ⭐ Zastosowane**: `SessionAuthenticator(cookieJar, sessionRepository) : Authenticator` — dodano do Phase 2. Na 401: `clear() + setLoggedIn(false) + return null`. NetworkModule zaktualizowany. Brak circular dep.
- **Decision**: FIXED via Fix A

### F2 — Metoda login() nie obsługuje wyjątków sieciowych

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — szybka decyzja; fix jest oczywisty i wąski
- **Dimension**: Blind Spots
- **Location**: Phase 2 — AuthRepository, metoda login()
- **Detail**: Obsługa HTTP status codes opisana, ale IOException z sieci nieobsłużona — crash lub silent failure w viewModelScope.
- **Fix**: Dodano w kontrakcie login() że metoda jest owrapowana w `try { } catch (e: Exception) { return Result.failure(e) }`.
- **Decision**: FIXED

### F3 — Strategia parsowania body 422 nie jest określona

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — szybka decyzja; fix jest oczywisty i wąski
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — AuthRepository, case 422
- **Detail**: "parsuje body JSON" bez modelu ani mechanizmu. `response.errorBody()` jednorazowy, brak definicji `ErrorResponse` klasy.
- **Fix**: Dodano `LoginErrorResponse(@Serializable data class)` do Phase 2 + pełny snippet parsowania w kontrakcie login().
- **Decision**: FIXED

### F4 — getCurrentUser() w AuthApiService jest dead code w F-01

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — szybka decyzja
- **Dimension**: Lean Execution
- **Location**: Phase 1 — AuthApiService
- **Detail**: Metoda nigdy nie wywoływana w F-01. Przydatna dla S-01.
- **Fix**: Usunięto `getCurrentUser()` z `AuthApiService` w F-01. Przywrócić w planie S-01.
- **Decision**: FIXED

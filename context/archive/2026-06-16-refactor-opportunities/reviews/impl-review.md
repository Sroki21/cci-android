<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Refactor Opportunities

- **Plan**: context/changes/refactor-opportunities/plan.md
- **Scope**: Fazy 1–3 of 3 (pełny review)
- **Date**: 2026-06-16
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical | 2 warnings | 2 observations

## Verdicts

| Dimension           | Verdict |
|---------------------|---------|
| Plan Adherence      | PASS    |
| Scope Discipline    | WARNING |
| Safety & Quality    | WARNING |
| Architecture        | PASS    |
| Pattern Consistency | PASS    |
| Success Criteria    | PASS    |

## Findings

### F1 — Token Bearer prefix logowany do Logcata

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: SessionRepository.kt:72
- **Detail**: `raw.take(40)` logowało pierwsze 40 znaków surowej odpowiedzi z tokenem. Dla plain string lub krótkiego JWT pierwsze 40 znaków to znaczny fragment.
- **Fix A ⭐ Recommended**: Zastąp `raw prefix=${raw.take(40)}` przez `raw length=${raw.length}`
  - Strength: Eliminuje ryzyko; wciąż wiadomo czy body było puste.
  - Tradeoff: Minimalny — 1 call site.
  - Confidence: HIGH — wzorzec spójny z logów w linii 63 i 40 AuthRepository.
  - Blind spot: Utracisz podgląd surowej treści przy debugowaniu.
- **Fix B**: Usuń cały fragment `raw prefix=...` z logu
  - Strength: Zero ryzyka wycieków, minimalizm.
  - Tradeoff: Utrudnia debugging gdy token jest null.
  - Confidence: HIGH.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — `raw.take(40)` → `raw.length`

### F2 — Dwa pliki testowe poza planem (SessionAuthenticatorTest, HomeViewModelTest)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: SessionAuthenticatorTest.kt:27 | HomeViewModelTest.kt:41
- **Detail**: Plan nie przewidywał zmian w tych plikach. `dagger.Lazy<AuthApiService>` wymusił aktualizację wszystkich konstruktorów SessionRepository w testach. Zmiany minimalne i semantycznie neutralne.
- **Fix**: Dopisz addendum w Changes Required Phase 3 w plan.md.
- **Decision**: FIXED — addendum dopisane w plan.md pod Phase 3

### F3 — Duplikat Json companion object w AuthRepository

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: AuthRepository.kt:22-24 | SessionRepository.kt:23-25
- **Detail**: Obie klasy mają identyczny `companion object { private val json = Json { ignoreUnknownKeys = true } }`. Odrębne use case'y (422 parsing vs token parsing) — nie błąd.
- **Fix**: Wyciągnij do `internal object AppJson` gdy projekt będzie potrzebował wspólnej konfiguracji.
- **Decision**: SKIPPED

### F4 — dagger.Lazy — brak komentarza wyjaśniającego cykl

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: SessionRepository.kt:20
- **Detail**: `dagger.Lazy<AuthApiService>` to pierwsze bezpośrednie użycie `dagger.*` w produkcji — powód Lazy może być nieoczywisty dla przyszłego autora.
- **Fix**: Dopisz jednolinijkowy komentarz przy polu konstruktora.
- **Decision**: FIXED — komentarz wyjaśniający cykl Hilt dopisany przy polu `authApiService`

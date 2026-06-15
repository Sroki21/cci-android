<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Testy Firestore dual-write, restore concurrency i slot uniqueness

- **Plan**: context/changes/test-firestore-restore-and-slots/plan.md
- **Scope**: Full plan (Phase 1 + Phase 2)
- **Date**: 2026-06-15
- **Verdict**: APPROVED
- **Findings**: 0 critical · 1 warning · 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Test concurrency opiera się na kooperatywnym schedulingu

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — prawdziwy trade-off; warto zrozumieć założenie
- **Dimension**: Pattern Consistency
- **Location**: FirestoreRestoreUseCaseTest.kt:111
- **Detail**: `runBlocking` + `delay(50)` osiąga interleaving przez kooperatywne zawieszenie na BlockingEventLoop, nie prawdziwe równoległe wątki. Test poprawnie rozróżnia kod z Mutex od bez Mutex, ale mechanizm jest nieoczywisty — zamiana na `runTest` złamałaby model.
- **Fix**: Dodaj load-bearing komentarz tłumaczący mechanizm i ostrzegający przed zamianą na `runTest`.
- **Decision**: FIXED — komentarz dodany

### F2 — failingCapDao bez relaxed = true

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: FirestoreRestoreUseCaseTest.kt:88
- **Detail**: `mockk<CapPositionDao>()` bez `relaxed = true`. Aktualnie bezpieczne (jedyna wywołana metoda jest stubowana), ale przy rozbudowie DAO ryzyko `MockKException` zamiast asercji testu.
- **Fix**: `mockk<CapPositionDao>(relaxed = true)`.
- **Decision**: FIXED

### F3 — Fully qualified SQLiteConstraintException w catch

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: CapPositionRepositoryTest.kt:98
- **Detail**: `catch (e: android.database.sqlite.SQLiteConstraintException)` używa fully qualified name mimo istniejącego importu w linii 4.
- **Fix**: Skróć do `catch (e: SQLiteConstraintException)`.
- **Decision**: FIXED

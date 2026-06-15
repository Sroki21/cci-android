<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Phase A — Data Integrity Tests

- **Plan**: context/changes/test-collection-verifier-and-auth/plan.md
- **Scope**: All 3 Phases
- **Date**: 2026-06-15
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical  4 warnings  2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Concurrency test vacuous pod StandardTestDispatcher

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: CollectionVerifierTest.kt:187–205
- **Detail**: `runTest {}` używa domyślnie `StandardTestDispatcher` (sekwencyjny). Korutyny z Semaphore(4) wykonywane były jedna po drugiej — maxObserved nigdy nie przekraczało 1, asercja `<= 4` zawsze trivially true. Regresja do Semaphore(1) byłaby niewidoczna.
- **Fix A ⭐**: `runTest(UnconfinedTestDispatcher())` — coroutines startują natychmiast, równolegle przy delay. Minimalna zmiana (1 linia); test rzeczywiście sprawdza współbieżność.
- **Decision**: FIXED via Fix A (commit 4754457) — dodano `@OptIn(ExperimentalCoroutinesApi::class)` + `UnconfinedTestDispatcher()`.

### F2 — isCancelled test: atMost=1 pozwala na 0 wywołań

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: CollectionVerifierTest.kt:180–183
- **Detail**: `coVerify(atMost = 1)` przechodzi gdy upsertSnapshot wywołane 0 lub 1 raz. Jeśli scan przerwie się zanim przetworzy jakikolwiek element, test nadal przechodzi.
- **Fix**: Zmień `atMost = 1` → `exactly = 1` — wymusza dokładnie 1 przetworzony element.
- **Decision**: FIXED (commit 4754457).

### F3 — Test SWAPPED bez weryfikacji skutków ubocznych

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: CollectionVerifierTest.kt:148–155
- **Detail**: Test `verify — zmiana createdAt — zwraca SWAPPED` asertuje tylko wartość zwracaną. Nie sprawdza że `upsertSnapshot` NIE jest wywołane. Testy UPDATED i MISSING mają pełne weryfikacje.
- **Fix**: Dodaj `coVerify(exactly = 0) { capCacheRepository.upsertSnapshot(any(), any(), any(), any(), any(), any(), any()) }` do testu SWAPPED.
- **Decision**: FIXED — dodano `coVerify(exactly = 0)` po asercji zwracanej wartości.

### F4 — Plan drift: test "runBatch" wywołuje runFullScan()

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: CollectionVerifierTest.kt:187
- **Detail**: Plan mówi "runBatch — max 4 równoległe" sugerując bezpośrednie wywołanie `runBatch()`. Test wywołuje `verifier.runFullScan()` (bo `runBatch` jest prywatny — OK). Ale nazwa testu "runBatch" jest myląca.
- **Fix**: Zmień nazwę testu na `` `runFullScan — max 4 rownolegle wywolania verify` ``.
- **Decision**: FIXED — zmieniono nazwę testu na `` `runFullScan — max 4 rownolegle wywolania verify` ``.

### F5 — @Ignore błąd sieci: brakuje asercji propagacji wyjątku

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: FirebaseAuthManagerTest.kt:56–67
- **Detail**: Test "błąd sieci" weryfikuje tylko że `createUser` nie jest wywołane. Nie sprawdza że `IOException` propaguje się do wywołującego. Po naprawieniu buga: jeśli fix nie woła createUser ale błąd jest cicho połykany, test przejdzie mimo niepoprawnego zachowania.
- **Fix**: Gdy @Ignore zostanie usunięty, dodaj `runCatching { manager.signInWithEmail(...) }; assertTrue(result.isFailure)`.
- **Decision**: FIXED — zmieniono wywołanie na `runCatching { ... }` i dodano `assertTrue(result.isFailure)` + import `Assert.assertTrue`.

### F6 — SessionAuthenticatorTest: drugi test jest nadmiarowy

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SessionAuthenticatorTest.kt:67–72
- **Detail**: Test 2 ("authenticate — zwraca null (brak retry)") asertuje tylko `assertNull(result)` — co jest już pokryte przez Test 1 (linia 64). Dodaje noise bez unikalnego pokrycia.
- **Fix**: Rozszerz Test 2 o unikalny scenariusz (np. wywołanie gdy sesja już wylogowana) LUB usuń jako redundantny.
- **Decision**: FIXED — usunięto redundantny test `authenticate — zwraca null (brak retry)`.

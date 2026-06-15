<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Binder Management (S-02)

- **Plan**: context/changes/binder-management/plan.md
- **Zakres**: Phase 1 + Phase 2 (pełny plan)
- **Data**: 2026-06-11
- **Verdict**: NEEDS ATTENTION
- **Wyniki**: 0 critical · 5 warnings · 2 observations

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

### F1 — pageJobs nie czyszczone przy zewnętrznym usunięciu bindera

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: BindersViewModel.kt:49-54
- **Detail**: Kolektor `getAll()` aktualizuje tylko `uiState.binders`. Gdy Firestore sync usunie binder na innym urządzeniu, Room emituje nową listę bez tego bindera — ale `pageJobs[id]` nie jest anulowany, `expandedBinderIds` i `binderPages` nadal trzymają wpis dla nieistniejącego ID. Jeden orphaned coroutine per zewnętrznie usunięty binder, przez całe życie ViewModel-a.
- **Fix A ⭐ Recommended**: Rozszerzyć kolektor getAll() o cleanup starych IDs. Przy każdej emisji oblicz `removedIds = previousIds - newIds`; dla każdego usuniętego ID wywołaj `pageJobs.remove(id)?.cancel()` i wyczyść wpisy w `expandedBinderIds`/`binderPages`.
  - Strength: Atomicznie rozwiązuje wszystkie trzy stale states; analogiczne do logiki cancel w confirmDeleteBinder:75-96.
  - Tradeoff: Wymaga śledzenia `previousIds` — 2-3 linii więcej w init.
  - Confidence: HIGH.
  - Blind spot: Nie sprawdzono czy Room cascade delete zwalnia blokadę page Flow natychmiastowo.
- **Fix B**: Odłożyć na S-03 — brak Firestore sync w S-02 scope testowym.
  - Strength: Zero zmian w S-02; problem realny dopiero przy multi-device sync.
  - Tradeoff: Dług rośnie — przy S-03 trzeba pamiętać.
  - Confidence: MED.
  - Blind spot: Jeśli S-03 też nie dotyczy sync, dług będzie dalej odraczany.
- **Decision**: FIXED via Fix A

### F2 — Brak ochrony przed double-submit w createBinder

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: BindersViewModel.kt:75 + BindersScreen.kt:158-162
- **Detail**: Dwa szybkie tapnięcia "Zapisz" w CreateBinderDialog startują dwa równoległe coroutines → dwa `binderDao.insert()` → zduplikowane bindera. Ten sam problem dotyczy "Dodaj stronę" — dwa coroutines mogą przejść przez `check(count < 15)` i wstawić dwie strony z identycznym `pageNumber`.
- **Fix**: Dodać `isLoading: Boolean = false` do `BindersUiState`; ustawić na `true` na wejściu do każdej mutującej coroutine (createBinder, addPage), `false` w `finally`. Powiązać `enabled = !uiState.isLoading` z FAB i przyciskami.
  - Strength: Jeden flag obsługuje wszystkie ścieżki mutacji.
  - Tradeoff: Nieco szerszy state.
  - Confidence: HIGH.
  - Blind spot: Nie zweryfikowano czy binderDao ma UNIQUE constraint na name.
- **Decision**: FIXED

### F3 — Nieobsłużone wyjątki DB/IO w metodach ViewModel

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: BindersViewModel.kt:77-83, 92-99, 107-113
- **Detail**: `createBinder` łapie tylko `IllegalArgumentException`, `confirmDeleteBinder` i `addPage` tylko `IllegalStateException`. Każda inna Throwable (SQLiteException, IOException) cicho uśmierca coroutine bez feedbacku. Szczególnie ryzykowne w `createBinder` — wyjątek Firestore przed `insert()` zostawia dialog otwarty ze stanem niespójnym.
- **Fix**: Dodać `catch (e: Exception)` jako fallback we wszystkich trzech metodach, emitując generyczny Snackbar.
  - Strength: Jeden wzorzec, 3 miejsca; każdy błąd staje się widoczny.
  - Tradeoff: Szeroki catch może maskować błędy programistyczne.
  - Confidence: HIGH.
  - Blind spot: CancellationException jest automatycznie re-rzucony przez Kotlin coroutines — `catch (e: Exception)` go nie przechwytuje.
- **Decision**: FIXED

### F4 — Martwy catch(IllegalStateException) w confirmDeletePage

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: BindersViewModel.kt:121-128
- **Detail**: `BinderPageRepository.deletePage()` nie zawiera żadnego `check()`. Catch clause jest martwym kodem; błąd DAO zostanie cicho połknięty.
- **Fix**: Zastąpić `catch (e: IllegalStateException)` przez `catch (e: Exception)`.
- **Decision**: FIXED

### F5 — Icon drift: Folder zamiast FolderOpen w HomeScreen

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: HomeScreen.kt:62
- **Detail**: Plan wymagał `Icons.Filled.FolderOpen`. Implementacja używa `Icons.Filled.Folder`. material-icons-extended jest już w build.gradle.
- **Fix**: Zmienić `Icons.Filled.Folder` → `Icons.Filled.FolderOpen` w HomeScreen.kt:62.
- **Decision**: FIXED

### F6 — events.first() w testach może wisieć bez timeoutu

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: BindersViewModelTest.kt:63, 75, 84
- **Detail**: Działa poprawnie z UnconfinedTestDispatcher (event w buforze). Zmiana na StandardTestDispatcher → zawieszenie bez komunikatu.
- **Fix**: Owinąć w `withTimeout(1_000) { viewModel.events.first() }`.
- **Decision**: FIXED

### F7 — TOCTOU race w BinderPageRepository.addPage

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: BinderPageRepository.kt:26 (poza diff; ujawniony przez VM)
- **Detail**: `countByBinderId()` + `check(count < 15)` + `insert()` nie są w Room `@Transaction`. Dwa równoległe `addPage(binderId)` mogą oba przejść check. UNIQUE index na `(binder_id, page_number)` istnieje i rzuci `SQLiteConstraintException` — niezagospodarowany wyjątek w VM (powiązane z F3/F4).
- **Fix**: Przenieść count + check + insert do Room `@Transaction` w DAO lub Repository.
- **Decision**: FIXED

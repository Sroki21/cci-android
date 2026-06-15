---
change_id: test-firestore-restore-and-slots
type: plan-brief
created: 2026-06-15
---

# Plan Brief — test-firestore-restore-and-slots

## Co robimy

3 nowe testy androidTest (Phase C z test-plan.md) + naprawa TOCTOU:

| Test | Ryzyko | Oczekiwany wynik |
|------|--------|-----------------|
| `CapPositionRepositoryTest.reassignFull_targetOccupied_rollsBack` | R7 FR-012 guardrail | PASS (SQLiteConstraintException + rollback) |
| `FirestoreRestoreUseCaseTest.restoreFromFirestore_partialFailure_rollsBack` | R4 dual-write | PASS (withTransaction rollback) |
| `FirestoreRestoreUseCaseTest.restoreIfEmpty_concurrentCalls_noDuplicates` | R5 TOCTOU | PASS po Mutex fix |

## Kluczowe decyzje projektowe

- **Nowy plik** `FirestoreRestoreUseCaseTest.kt` z mockk (nie rozszerzamy istniejącego `FirestoreRestoreTest.kt` który używa real Firebase)
- **mockk-android:1.14.11** — dodajemy do `androidTestImplementation` (brakuje w obecnych zależnościach)
- **Mutex fix** — `private val restoreIfEmptyMutex = Mutex()` + `restoreIfEmptyMutex.withLock {}` w `restoreIfEmpty()`; bez naprawy test 3 byłby czerwony (TOCTOU tworzy 2 klasery)

## Fazy

**Phase 1 (R7)** — tylko `CapPositionRepositoryTest.kt`, brak nowych zależności
**Phase 2 (R4 + R5)** — `build.gradle`, nowy `FirestoreRestoreUseCaseTest.kt`, `FirestoreRestoreUseCase.kt`

## Kontrakt plików

```
app/build.gradle
  androidTestImplementation "io.mockk:mockk-android:1.14.11"   ← NEW

app/src/androidTest/.../data/CapPositionRepositoryTest.kt
  + reassignFull_targetOccupied_rollsBack()                     ← ADD

app/src/androidTest/.../data/FirestoreRestoreUseCaseTest.kt     ← NEW
  setUp(): in-memory Room + mockk authManager + 3 service mocks
  restoreFromFirestore_partialFailure_rollsBack()
  restoreIfEmpty_concurrentCalls_noDuplicates()

app/src/main/.../data/FirestoreRestoreUseCase.kt
  + private val restoreIfEmptyMutex = Mutex()                   ← ADD
  ~ restoreIfEmpty(): owinąć w restoreIfEmptyMutex.withLock {}  ← MODIFY
```

## Uwaga implementacyjna

`binderDao.getAll()` zwraca `Flow<List<Binder>>` — w teście `restoreFromFirestore_partialFailure_rollsBack` użyj `db.binderDao().countAll()` (suspend query) zamiast `getAll().first()` żeby uniknąć flow collection w runBlocking.

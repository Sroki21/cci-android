---
change_id: test-firestore-restore-and-slots
title: Testy Firestore dual-write, restore concurrency i slot uniqueness
status: planned
created: 2026-06-15
updated: 2026-06-15
---

## Overview

Phase C z `context/foundation/test-plan.md` — pokrycie ryzyk R4, R5, R7.

**3 nowe testy androidTest + 1 naprawa TOCTOU:**
- `CapPositionRepositoryTest.reassignFull_targetOccupied_rollsBack` — FR-012 guardrail (R7)
- `FirestoreRestoreUseCaseTest.restoreFromFirestore_partialFailure_rollsBack` — transaction rollback (R4)
- `FirestoreRestoreUseCaseTest.restoreIfEmpty_concurrentCalls_noDuplicates` — TOCTOU fix + test (R5)

**Infrastruktura:**
- `app/build.gradle` — dodaj `mockk-android:1.14.11` do `androidTestImplementation`
- `FirestoreRestoreUseCase.kt` — dodaj `Mutex` + `withLock {}` w `restoreIfEmpty()`

**Co odkryto podczas badania:**
- `CapPositionRepositoryTest.kt` i `FirestoreRestoreTest.kt` już istnieją z innymi testami — rozszerzamy istniejące pliki / tworzymy nowy.
- `CapPositionDao.reassignFull()` używa `@Insert` (ABORT) — rzuca `SQLiteConstraintException` na UNIQUE violation, `@Transaction` rollbackuje `deleteByCapId`.
- `restoreIfEmpty()` NIE używa `withTransaction` i nie ma `Mutex` → TOCTOU: dwa równoległe wywołania oba przechodzą check `countAll() > 0`, oba wywołują `insertRestored()` → duplikaty.
- `restoreFromFirestore()` używa `database.withTransaction {}` — rollback działa poprawnie.
- `mockk` jest tylko w `testImplementation` (JVM); `androidTestImplementation` potrzebuje `mockk-android`.

## What We're NOT Doing

- Nie naprawiamy R4 dual-write w `assign()`/`reassign()` (fire-and-forget Firestore schedulers poza zakresem).
- Nie dodajemy Mutex do `restoreFromFirestore()` — ma własną ochronę przez `database.withTransaction {}`.
- Nie rozszerzamy istniejącego `FirestoreRestoreTest.kt` (miesza wzorce real/mock; nowy plik jest czystszy).
- Nie piszemy testu dla "stara pozycja zwalnia się gdy reassign się powiedzie" — pokrywa to istniejący `reassign_movesCapToNewSlot`.

---

## Phase 1: Slot uniqueness coverage (R7)

**Zakres:** 1 test w istniejącym pliku androidTest.

### Changes Required

**`app/src/androidTest/java/pl/sroki/cci/android/data/CapPositionRepositoryTest.kt`**
Dodaj import `org.junit.Assert.fail` (jeśli jeszcze go nie ma).
Dodaj na końcu klasy:

```kotlin
@Test
fun reassignFull_targetOccupied_rollsBack() = runBlocking {
    // Setup: cap A at slot 1, cap B at slot 2
    repo.assign(binderPageId, 1, 42L)
    repo.assign(binderPageId, 2, 99L)

    // Attempt: move cap A to slot 2 (occupied by B)
    try {
        repo.reassign(42L, binderPageId, 2)
        fail("Expected SQLiteConstraintException — slot (page=$binderPageId, pos=2) is occupied")
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        // expected: @Insert(ABORT) in reassignFull throws, @Transaction rolls back deleteByCapId
    }

    // Verify rollback: both caps still in original slots
    val positions = repo.getByPage(binderPageId).first()
    assertEquals(42L, positions.first { it.position == 1 }.capId)
    assertEquals(99L, positions.first { it.position == 2 }.capId)
}
```

### Success Criteria

#### Automated
- `./gradlew connectedDebugAndroidTest --tests "*.CapPositionRepositoryTest"` — `reassignFull_targetOccupied_rollsBack` PASS

#### Manual
- Przejrzyj output testu: potwierdź że SQLiteConstraintException jest caught (nie inny wyjątek)

---

## Phase 2: FirestoreRestoreUseCase tests + TOCTOU fix (R4, R5)

**Zakres:** `mockk-android` dependency, nowy plik testowy, naprawa TOCTOU.

### Changes Required

**`app/build.gradle`**
W bloku `dependencies {}` dodaj linię po `testImplementation "io.mockk:mockk:1.14.11"`:
```groovy
androidTestImplementation "io.mockk:mockk-android:1.14.11"
```

**`app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt`**
1. Dodaj import: `import kotlinx.coroutines.sync.Mutex` i `import kotlinx.coroutines.sync.withLock`
2. Dodaj pole w klasie (po `@Inject constructor` przed pierwszą metodą):
   ```kotlin
   private val restoreIfEmptyMutex = Mutex()
   ```
3. Zmień `restoreIfEmpty()` — owiń logikę w `restoreIfEmptyMutex.withLock {}`:
   ```kotlin
   suspend fun restoreIfEmpty() {
       val uid = authManager.uid.value ?: return
       restoreIfEmptyMutex.withLock {
           if (binderDao.countAll() > 0) return
           val allBinders = binderService.fetchAll(uid)
           val allPages = binderPageService.fetchAll(uid)
           val allCaps = capPositionService.fetchAll(uid)
           insertRestored(chooseBinders(allBinders, allPages, allCaps), allPages, allCaps)
       }
   }
   ```

**Nowy plik: `app/src/androidTest/java/pl/sroki/cci/android/data/FirestoreRestoreUseCaseTest.kt`**

```kotlin
package pl.sroki.cci.android.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.BinderPageFirestoreService
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionDocument
import pl.sroki.cci.android.data.datasource.remote.firestore.CapPositionFirestoreService

@RunWith(AndroidJUnit4::class)
class FirestoreRestoreUseCaseTest {

    private companion object {
        const val TEST_UID = "test-uid-phase-c"
    }

    private lateinit var db: CciDatabase
    private val binderService = mockk<BinderFirestoreService>(relaxed = true)
    private val binderPageService = mockk<BinderPageFirestoreService>(relaxed = true)
    private val capPositionService = mockk<CapPositionFirestoreService>(relaxed = true)
    private val authManager = mockk<FirebaseAuthManager>()
    private lateinit var useCase: FirestoreRestoreUseCase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { authManager.uid } returns MutableStateFlow(TEST_UID)
        useCase = FirestoreRestoreUseCase(
            authManager = authManager,
            database = db,
            binderDao = db.binderDao(),
            binderPageDao = db.binderPageDao(),
            capPositionDao = db.capPositionDao(),
            capCacheDao = db.capCacheDao(),
            binderService = binderService,
            binderPageService = binderPageService,
            capPositionService = capPositionService
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun restoreFromFirestore_partialFailure_rollsBack() = runBlocking {
        // Pre-condition: 1 existing binder in Room
        db.binderDao().insert(Binder(name = "pre-existing"))

        // Firestore returns data so insertRestored() is reached
        coEvery { binderService.fetchAll(TEST_UID) } returns listOf(
            BinderDocument("fsB1", "Restored Klaser")
        )
        coEvery { binderPageService.fetchAll(TEST_UID) } returns listOf(
            BinderPageDocument("fsP1", "fsB1", 1)
        )
        coEvery { capPositionService.fetchAll(TEST_UID) } returns listOf(
            CapPositionDocument("fsCap1", "fsP1", 1, 42L)
        )

        // capPositionDao throws on insertOrIgnore → triggers rollback of database.withTransaction
        val failingCapDao = mockk<CapPositionDao>()
        coEvery { failingCapDao.insertOrIgnore(any()) } throws SQLiteException("forced failure in test")
        val failingUseCase = FirestoreRestoreUseCase(
            authManager, db, db.binderDao(), db.binderPageDao(),
            failingCapDao, db.capCacheDao(),
            binderService, binderPageService, capPositionService
        )

        var threw = false
        try {
            failingUseCase.restoreFromFirestore()
        } catch (e: Exception) {
            threw = true
        }

        assertTrue("restoreFromFirestore powinno rzucić wyjątek gdy DAO zawiedzie", threw)
        // Rollback: pre-existing binder is back, no new binders
        val binders = db.binderDao().getAll()
        runBlocking {
            // Czytamy przez suspend query lub coroutine
        }
        assertEquals(
            "database.withTransaction rollback: pre-existing binder powinien wrócić",
            1, db.binderDao().countAll()
        )
    }

    @Test
    fun restoreIfEmpty_concurrentCalls_noDuplicates() = runBlocking {
        // delay(50) powoduje że oba coroutiny przechodzą check countAll()==0 przed insertem
        coEvery { binderService.fetchAll(TEST_UID) } coAnswers {
            delay(50)
            listOf(BinderDocument("fsB1", "Test Klaser"))
        }
        coEvery { binderPageService.fetchAll(TEST_UID) } returns emptyList()
        coEvery { capPositionService.fetchAll(TEST_UID) } returns emptyList()

        val job1 = launch { useCase.restoreIfEmpty() }
        val job2 = launch { useCase.restoreIfEmpty() }
        joinAll(job1, job2)

        // Po naprawie TOCTOU (Mutex w restoreIfEmpty): 1 klaser
        assertEquals(
            "Mutex w restoreIfEmpty: drugie wywołanie widzi countAll>0 i zwraca wcześniej",
            1, db.binderDao().countAll()
        )
    }
}
```

**Uwaga:** Jeśli `FirebaseAuthManager` jest klasą `final` (domyślnie w Kotlin), `mockk<FirebaseAuthManager>()` wymaga `mockk-android` z instrumentacją dexmaker. Jeśli mockk rzuci `MockKException: Can't mock final class`, dodaj do `androidTest/resources/mockito-extensions/` plik lub sprawdź konfigurację `all-open` pluginu. Alternatywa: `spyk(FirebaseAuthManager(mockk(relaxed = true)))`.

### Success Criteria

#### Automated
- `./gradlew compileDebugAndroidTestKotlin` — kompilacja bez błędów
- `./gradlew connectedDebugAndroidTest --tests "*.FirestoreRestoreUseCaseTest"` — oba testy PASS
- `./gradlew connectedDebugAndroidTest --tests "*.CapPositionRepositoryTest"` — nadal PASS (regresja Phase 1)

#### Manual
- Potwierdź że `restoreFromFirestore_partialFailure_rollsBack` shows `binderCount=1` after rollback
- Potwierdź że `restoreIfEmpty_concurrentCalls_noDuplicates` shows `binderCount=1` (Mutex działa)
- Potwierdź że `FirestoreRestoreTest.restoreIfEmpty_rebuildsHierarchy` nadal PASS (brak regresji)

---

## Progress

### Phase 1: Slot uniqueness coverage (R7)

#### Automated
- [x] 1.1 Kompilacja — `./gradlew compileDebugAndroidTestKotlin` — brak błędów
- [x] 1.2 `connectedDebugAndroidTest --tests "*.CapPositionRepositoryTest"` — `reassignFull_targetOccupied_rollsBack` PASS

#### Manual
- [x] 1.3 Potwierdź wyjątek w logach testu: `SQLiteConstraintException` caught (nie fail/error)

### Phase 2: FirestoreRestoreUseCase tests + TOCTOU fix (R4, R5)

#### Automated
- [ ] 2.1 Kompilacja — `./gradlew compileDebugAndroidTestKotlin` — brak błędów po dodaniu mockk-android
- [ ] 2.2 `connectedDebugAndroidTest --tests "*.FirestoreRestoreUseCaseTest"` — oba testy PASS
- [ ] 2.3 `connectedDebugAndroidTest --tests "*.CapPositionRepositoryTest"` — brak regresji
- [ ] 2.4 `connectedDebugAndroidTest --tests "*.FirestoreRestoreTest"` — brak regresji w istniejących testach

#### Manual
- [ ] 2.5 Sprawdź logi: `restoreFromFirestore_partialFailure_rollsBack` — binderCount=1 po rollback
- [ ] 2.6 Sprawdź logi: `restoreIfEmpty_concurrentCalls_noDuplicates` — binderCount=1 (Mutex)

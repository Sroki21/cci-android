---
project: CCI Android
created: 2026-06-15
updated: 2026-06-15
version: 1
---

# Test Plan — CCI Android

## §1 Zasady

1. **Cost × signal.** Każdy test musi odpowiedzieć: *jaki jest najtańszy test dający realny sygnał dla tego ryzyka?* JVM unit testy przed instrumentowanymi; instrumentowane tylko gdy Room, Firestore lub Android system jest bezpośrednim przedmiotem testu.

2. **Obawy użytkownika są dowodem.** Ryzyka wskazane przez właściciela mają wagę równą liniom PRD i danym hot-spot. Trzy obszary szczególnej troski: (1) błędny status posiadania kapsla, (2) synchronizacja Room–Firestore, (3) cztery obszary bez pokrycia (CollectionVerifier, migracje 3→6, signInWithEmail, restoreIfEmpty).

3. **Sygnał, nie wiedza.** Plan cytuje katalogi hot-spot i linie PRD jako dowód prawdopodobieństwa. Dokładne anchory call-graph (plik:linia) są zadaniem `/10x-research` podczas każdej fazy rollout.

---

## §2 Mapa ryzyk

| # | Ryzyko | Prawdop. | Wpływ | Dowody |
|---|--------|----------|-------|--------|
| R1 | **CollectionVerifier — błędny CatalogStatus** | WYSOKI | WYSOKI | User Q1 ("błędny status posiadania"); hot-spot `ui/statistics/verification/` (2 commit/30d); zero testów jednostkowych |
| R2 | **Migracje Room 3→4, 4→5, 5→6 — brak testów** | WYSOKI | WYSOKI | User Q4 explicit; `MigrationTest.kt` pokrywa tylko 1→2 i 6→7; trzy środkowe migracje (cap_cache + snapshot + fingerprint columns) bez żadnego testu |
| R3 | **FirebaseAuthManager.signInWithEmail — fallback create na dowolnym wyjątku** | WYSOKI | WYSOKI | User Q4 explicit; złe hasło → exception caught → `createUserWithEmailAndPassword` → duplikat konta Firebase oddzielony od konta crowncaps.info |
| R4 | **Dual-write Room–Firestore — spójność przy awarii** | WYSOKI | ŚREDNI | User Q3 ("obszar bez pewności"); hot-spot `data/` (4 commit/30d); `restoreIfEmpty` bez transakcji; zapis do Firestore może odejść gdy Room nie zaktualizowany |
| R5 | **FirestoreRestoreUseCase.restoreIfEmpty — TOCTOU** | ŚREDNI | ŚREDNI | User Q4 explicit; check `countAll() > 0` → network fetch → insert bez transakcji — dwa równoległe wywołania tworzą duplikaty w Room |
| R6 | **checkSession — stale Bearer token = isLoggedIn=true** | ŚREDNI | ŚREDNI | PRD FR-002 (backward compat browsing); `AuthRepository.checkSession`: `hasSession \|\| cachedToken != null` — wygasły token daje UI "zalogowany" a API zwraca 401 |
| R7 | **Slot uniqueness FR-012 — brak pokrycia testowego** | NISKI | WYSOKI | PRD: "guardrail bezwzględny"; `CapPositionRepository.reassignFull` — `@Transaction` w Room ale bez testu weryfikującego że UniqueConstraint faktycznie blokuje konflikt |

---

## §3 Fazy rollout

### Phase A — Data Integrity: CollectionVerifier + Auth — status: done

**Zakres:** R1, R3, R6

**Testy do dodania (unit JVM, mockk + runTest):**
- `CollectionVerifierTest` — `verify()` dla każdego `CatalogStatus`: UNKNOWN (baseline — brak snapshota), OK (fingerprinty zgodne), UPDATED (zmiany mutowalne), SWAPPED (zmiana createdAt/createdById), MISSING (kapsel usunięty z API); `runIncremental()` z Semaphore(4) — max 4 równoległe; `isCancelled()` mid-batch
- `FirebaseAuthManagerTest` — `signInWithEmail` sukces; złe hasło nie wywołuje `createUser`; błąd sieci rzuca wyjątek bez tworzenia konta
- `AuthRepositoryTest` (uzupełnienie) — `checkSession` z ważnym tokenem → `isLoggedIn=true`; `checkSession` z tokenem bez ważnej sesji → wymaga ponownego logowania (weryfikacja intencji FR-003)

**Change ID:** `test-collection-verifier-and-auth`

---

### Phase B — Migration Safety: Room 3→4, 4→5, 5→6 — status: done

**Zakres:** R2

**Testy do dodania (instrumentowane, `MigrationTestHelper`):**
- `MigrationTest.migrate3to4` — tabela `cap_cache` tworzona poprawnie; istniejące dane z v3 nienaruszone
- `MigrationTest.migrate4to5` — dodane kolumny; wartości NULL gdzie wymagane; istniejące wiersze nienaruszone
- `MigrationTest.migrate5to6` — kolumny snapshot (`name`, `country`, `imageUrl`) i fingerprint (`createdAt`, `createdById`, `updatedAt`) — NULL defaults poprawne
- `MigrationTest.migrateFullChain3to7` — upgrade od v3 do v7 przez wszystkie 4 migracje naraz; dane z v3 nie znikają; schemat końcowy zgodny z oczekiwanym

**Change ID:** `test-room-migrations`

---

### Phase C — Firestore Dual-Write + Slot Uniqueness — status: done

**Zakres:** R4, R5, R7

**Testy do dodania (instrumentowane, Room in-memory + mockk Firestore):**
- `FirestoreRestoreUseCaseTest.restoreIfEmpty_concurrentCalls_noDuplicates` — dwa równoległe wywołania `restoreIfEmpty()` nie tworzą zduplikowanych wierszy w Room
- `FirestoreRestoreUseCaseTest.restoreFromFirestore_partialFailure_rollsBack` — wyjątek w trakcie `database.withTransaction {}` → Room nie zawiera niekompletnych danych
- `CapPositionRepositoryTest.reassignFull_slotUniqueness` — próba przypisania kapsla do zajętej pozycji rzuca wyjątek lub zwraca błąd (FR-012 guardrail); stara pozycja zwalnia się gdy reassign się powiedzie

**Change ID:** `test-firestore-restore-and-slots`

---

## §4 Stack

- **Język:** Kotlin 2.1.20
- **Test runner (JVM):** JUnit 4 + `io.mockk:mockk` + `kotlinx-coroutines-test` (`runTest`, `TestCoroutineDispatcher`)
- **Test runner (instrumentowane):** `androidx.test.ext:junit` + Room `MigrationTestHelper` + Room `inMemoryDatabaseBuilder`
- **CI:** `.github/workflows/android.yml` uruchamia `./gradlew testDebugUnitTest` — **tylko testy JVM**. Phase B i C (instrumentowane) wymagają urządzenia/emulatora — nie są w bieżącym CI. Rozszerzenie CI o emulator to kandydat do Phase C lub osobnej zmiany.
- **Stack grounding tools (bieżąca sesja):** brak MCP docs/search; rekomendacje oparte na wiedzy modelu i odczycie istniejących testów w projekcie.

---

## §5 Czego NIE testować

- **UI Composables (Compose instrumented tests)** — prywatna aplikacja, 1 użytkownik; blast radius niski; business logic w warstwie Repository jest wystarczającym sygnałem jakości. *(User Q5)*
- **ViewModele z prostym pass-through** — ViewModel który tylko deleguje do Repository bez własnej logiki (np. `LatestCapsViewModel`, `CountriesListViewModel`) — wartość testu jednostkowego niska. *(User Q5)*
- **Zewnętrzne API crowncaps.info** — nie kontrolujemy serwera; testujemy jak aplikacja reaguje na odpowiedzi (sukces, 422, błąd sieci), nie co serwer zwraca. *(User Q5 + PRD Constraints)*

---

## §6 Cookbook

*Uzupełniane podczas każdej fazy przez `/10x-implement`.*

### Phase A — Data Integrity

*Pending — otwórz `/10x-new test-collection-verifier-and-auth`.*

### Phase B — Migration Safety

*Pending — otwórz `/10x-new test-room-migrations`.*

### Phase C — Firestore Dual-Write + Slot Uniqueness

*Pending — otwórz `/10x-new test-firestore-restore-and-slots`.*

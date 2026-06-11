# Firestore Sync — Plan implementacji

## Overview

Dodanie synchronizacji danych klaserów z Firebase Firestore jako backup dla prywatnej kolekcji kapsli.
Room pozostaje lokalnym cache (offline-first); każdy zapis do Room trafia jednocześnie do Firestore.
Przy pierwszej instalacji / wymianie urządzenia dane są odtwarzane z Firestore do Room automatycznie.

## Current State Analysis

- **Room v1** gotowy (F-02): 4 tabele — `pending_cap`, `binder`, `binder_page`, `cap_position`
- **Repozytoria**: `BinderRepository`, `BinderPageRepository`, `CapPositionRepository` — @Singleton, Hilt DI
- **Brak Firebase**: zero zależności Firebase w projekcie; `build.gradle` (root) nie zawiera google-services plugin
- **CCIApplication.kt**: `@HiltAndroidApp class CCIApplication : Application()` — puste, gotowe na `@Inject`
- **Room encje** nie mają pola `firestoreId` — wymaga migracji v1→v2
- **Wzorzec modułu DI**: `di/NetworkModule.kt` i `di/DatabaseModule.kt` — do powielenia dla `FirestoreModule.kt`

### Key Discoveries

- `build.gradle` (root):5 — plugins block; tu trafia `id 'com.google.gms.google-services' version '4.4.2' apply false`
- `app/build.gradle`:1–11 — plugins block; tu trafia `id 'com.google.gms.google-services'`
- `data/datasource/local/entity/Binder.kt` — brak `firestoreId`; Room v1
- `data/datasource/local/CciDatabase.kt` — `version = 1`, `exportSchema = false`; tu trafia Migration + version bump
- `di/DatabaseModule.kt`:24 — `fallbackToDestructiveMigration(dropAllTables = true)` — zostawiamy, dodajemy `.addMigrations()`
- `CCIApplication.kt`:6 — puste `Application()`; tu dodajemy `@Inject` i coroutine scope

## Desired End State

Po ukończeniu wszystkich 3 faz:
- Każde create/update/delete Bindera/BinderPage/CapPosition zapisuje dane do Room i Firestore jednocześnie
- Przy utracie urządzenia: nowa instalacja → dane klaserów odtworzone z Firestore automatycznie
- Anonymous Firebase UID trwały na urządzeniu (Firebase SDK persystuje między sesjami)
- Room schema: version 2 z kolumną `firestore_id` w tabelach binder, binder_page, cap_position

### Weryfikacja końcowa

- Utwórz klaser → widoczny w Firebase Console pod `users/{uid}/binders`
- Wyczyść dane aplikacji (lub zainstaluj na nowym urządzeniu z tym samym UID) → klaser odtworzony w Room
- Brak sieci → create Binder zapisuje do Room; po przywróceniu sieci dokument pojawia się w Firestore

## What We're NOT Doing

- Brak synchronizacji `PendingCap` (dane przejściowe, mniej krytyczne dla backupu)
- Brak real-time listenerów Firestore → Room (sync jest jednostronny: Room → Firestore + restore)
- Brak Firebase Email Auth (anonymous UID wystarczy dla prywatnej aplikacji)
- Brak multi-user / współdzielenia kolekcji
- Brak UI wykrywania konfliktów (last-write-wins bez pytania użytkownika)
- Brak periodic background sync (WorkManager) — write-through jest wystarczające
- Brak Room export schema (exportSchema = false pozostaje)

## Implementation Approach

**Write-through**: każde repozytorium injekuje odpowiedni `*FirestoreService` i `FirebaseAuthManager`.
Przy create: Firestore `DocumentReference.document()` generuje ID lokalnie (działa offline) → ID zapisywany
do Room jako `firestoreId`. Przy delete: kaskada ręczna w repozytoriach (Room FK cascade + Firestore batch).

**Restore**: `FirestoreRestoreUseCase` sprawdza przy starcie czy Room jest puste; jeśli tak i Firestore ma dane
dla UID — pobiera i odtwarza hierarchię w kolejności: Binder → BinderPage → CapPosition,
budując mapę `firestoreId → roomId` do wiązania relacji.

## Critical Implementation Details

- **Firestore ID generowane lokalnie**: `collection.document().id` zwraca UUID natychmiast, bez sieci.
  Pełny zapis do Firestore (`set()`) jest kolejkowany przez SDK i wysyłany gdy sieć wróci —
  nie wymaga `await()` w write-through; jest to fire-and-forget.
- **Kolejność w restore**: BinderPage.binderFirestoreId i CapPosition.binderPageFirestoreId
  są Firestore String ID, nie Room Long ID — mapa firestoreId→roomId budowana podczas restore
  jest krytyczna do poprawnego odtworzenia relacji FK.
- **Room migration v1→v2**: `ALTER TABLE ... ADD COLUMN firestore_id TEXT` (nullable, bez DEFAULT).
  Istniejące wiersze będą miały `firestore_id = NULL` — normalny stan dla danych sprzed F-03.
  Write-through w Phase 2 uzupełni `firestoreId` tylko dla nowo tworzonych rekordów,
  nie backfilluje istniejących.

---

## Phase 1: Firebase infra + Room migration v1→v2

### Ważne: PREREQIUSITE MANUALNY

Przed startem tej fazy użytkownik musi:
1. Stworzyć projekt Firebase w [Firebase Console](https://console.firebase.google.com/)
2. Dodać aplikację Android (`pl.sroki.cci.android`)
3. Pobrać `google-services.json` i umieścić w `app/`
4. W Firebase Console → Firestore → Utwórz bazę (tryb produkcji)
5. W Firebase Console → Authentication → Sign-in method → Anonymous → Włącz

### Overview

Dodanie Firebase SDK do projektu, migracja Room v1→v2 (kolumna `firestoreId`), moduł Hilt dla
Firebase, `FirebaseAuthManager` obsługujący anonymous sign-in.

### Changes Required:

#### 1. `build.gradle` (root)

**File**: `build.gradle`

**Intent**: Dodaj wtyczkę Google Services do classpath projektu.

**Contract**: W bloku `plugins` dodaj jako ostatni wpis:
```groovy
id 'com.google.gms.google-services' version '4.4.2' apply false
```

#### 2. `app/build.gradle`

**File**: `app/build.gradle`

**Intent**: Zastosuj wtyczkę google-services i dodaj zależności Firebase.

**Contract**: W bloku `plugins` (na końcu) dodaj `id 'com.google.gms.google-services'`.
W bloku `dependencies` dodaj:
```groovy
implementation platform('com.google.firebase:firebase-bom:33.8.0')
implementation 'com.google.firebase:firebase-firestore-ktx'
implementation 'com.google.firebase:firebase-auth-ktx'
```

#### 3. Encja `Binder`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/Binder.kt`

**Intent**: Dodaj pole `firestoreId` przechowujące Firestore document ID po pierwszym zapisie do chmury.

**Contract**: Dodaj do data class: `@ColumnInfo(name = "firestore_id") val firestoreId: String? = null`

#### 4. Encja `BinderPage`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/BinderPage.kt`

**Intent**: Analogicznie do Binder — pole `firestoreId`.

**Contract**: `@ColumnInfo(name = "firestore_id") val firestoreId: String? = null`

#### 5. Encja `CapPosition`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/CapPosition.kt`

**Intent**: Analogicznie do Binder — pole `firestoreId`.

**Contract**: `@ColumnInfo(name = "firestore_id") val firestoreId: String? = null`

#### 6. `CciDatabase.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/CciDatabase.kt`

**Intent**: Bump version do 2, zdefiniuj Migration 1→2 dodającą kolumnę `firestore_id` do 3 tabel.

**Contract**: version = 2. Dorzuć obiekt companion:
```kotlin
companion object {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE binder ADD COLUMN firestore_id TEXT")
            db.execSQL("ALTER TABLE binder_page ADD COLUMN firestore_id TEXT")
            db.execSQL("ALTER TABLE cap_position ADD COLUMN firestore_id TEXT")
        }
    }
}
```

#### 7. `DatabaseModule.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/di/DatabaseModule.kt`

**Intent**: Zarejestruj migrację 1→2 w builderze Room.

**Contract**: Dodaj `.addMigrations(CciDatabase.MIGRATION_1_2)` przed `.fallbackToDestructiveMigration(dropAllTables = true)`.

#### 8. `FirebaseAuthManager.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/data/FirebaseAuthManager.kt`

**Intent**: Singleton obsługujący anonymous sign-in i udostępniający UID jako StateFlow.

**Contract**:
```kotlin
@Singleton
class FirebaseAuthManager @Inject constructor(private val auth: FirebaseAuth) {
    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    val uid: StateFlow<String?> = _uid.asStateFlow()

    suspend fun ensureSignedIn() {
        if (auth.currentUser != null) { _uid.value = auth.currentUser?.uid; return }
        val result = auth.signInAnonymously().await()
        _uid.value = result.user?.uid
    }
}
```
Wymaga importu `com.google.firebase.auth.FirebaseAuth` i `kotlinx.coroutines.tasks.await`.

#### 9. `FirestoreModule.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/di/FirestoreModule.kt`

**Intent**: Moduł Hilt dostarczający FirebaseAuth i FirebaseFirestore jako Singletons.

**Contract**:
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object FirestoreModule {
    @Provides @Singleton fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
    @Provides @Singleton fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
}
```

#### 10. `MigrationTest.kt` (nowy plik)

**File**: `app/src/androidTest/java/pl/sroki/cci/android/data/MigrationTest.kt`

**Intent**: Weryfikuje że migracja 1→2 dodaje kolumny `firestore_id` bez utraty danych.

**Contract**: `@RunWith(AndroidJUnit4::class)` + `MigrationTestHelper`. Utwórz bazę v1 (z testowym Binderem), zmigruj do v2, sprawdź że Binder ma `firestore_id IS NULL` i `name` niezmieniony.
Wzorzec: `helper.runMigrationsAndValidate("cci_migration_test", 2, true, CciDatabase.MIGRATION_1_2)`.

### Success Criteria:

#### Automated Verification:

- Kompilacja: `./gradlew :app:compileDebugKotlin`
- KSP: `./gradlew :app:kspDebugKotlin`
- MigrationTest przechodzi: `./gradlew :app:connectedDebugAndroidTest`
- Ktlint: `./gradlew :app:ktlintCheck`

#### Manual Verification:

- Aplikacja uruchamia się bez crasha (Logcat czysty)
- Firebase Console → Authentication → anonimowy użytkownik pojawia się po pierwszym uruchomieniu

---

## Phase 2: Write-through sync (create/update/delete)

### Overview

Trzy klasy `*FirestoreService` (po jednej per encja) obsługują operacje CRUD w Firestore.
Repozytoria są rozszerzone o write-through: każdy zapis do Room poprzedza wygenerowanie Firestore ID,
każde usunięcie kaskaduje ręcznie przez Firestore przed usunięciem w Room.

### Changes Required:

#### 1. `BinderDao.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/BinderDao.kt`

**Intent**: Dodaj `getById` — potrzebny przez `BinderPageRepository` do odczytu `firestoreId` rodzica.

**Contract**: `@Query("SELECT * FROM binder WHERE id = :id LIMIT 1") suspend fun getById(id: Long): Binder?`

#### 2. `BinderPageDao.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/BinderPageDao.kt`

**Intent**: Dodaj `getById` — potrzebny przez `CapPositionRepository` do odczytu `firestoreId` rodzica.

**Contract**: `@Query("SELECT * FROM binder_page WHERE id = :id LIMIT 1") suspend fun getById(id: Long): BinderPage?`

#### 3. `BinderFirestoreService.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/BinderFirestoreService.kt`

**Intent**: Enkapsuluje operacje Firestore dla kolekcji `users/{uid}/binders`. Metody `schedule*` są
fire-and-forget (nie `suspend`) — Firestore SDK kolejkuje offline. `fetchAll` jest `suspend` — potrzebny
w Phase 3 do restore.

**Contract**:
```kotlin
@Singleton
class BinderFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {
    private fun col(uid: String) = firestore.collection("users/$uid/binders")

    fun scheduleCreate(uid: String, name: String): String {
        val ref = col(uid).document()
        ref.set(mapOf("name" to name, "updatedAt" to FieldValue.serverTimestamp()))
        return ref.id
    }

    fun scheduleUpdate(uid: String, firestoreId: String, name: String) {
        col(uid).document(firestoreId)
            .update("name", name, "updatedAt", FieldValue.serverTimestamp())
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    suspend fun fetchAll(uid: String): List<BinderDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            BinderDocument(firestoreId = doc.id, name = doc.getString("name") ?: return@mapNotNull null)
        }
}

data class BinderDocument(val firestoreId: String, val name: String)
```

#### 4. `BinderPageFirestoreService.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/BinderPageFirestoreService.kt`

**Intent**: Operacje CRUD w kolekcji `users/{uid}/binder_pages`. Dokumenty zawierają `binderFirestoreId`
(Firestore ID rodzica) — kluczowe dla restore w Phase 3.

**Contract**:
```kotlin
@Singleton
class BinderPageFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {
    private fun col(uid: String) = firestore.collection("users/$uid/binder_pages")

    fun scheduleCreate(uid: String, binderFirestoreId: String, pageNumber: Int): String {
        val ref = col(uid).document()
        ref.set(mapOf(
            "binderFirestoreId" to binderFirestoreId,
            "pageNumber" to pageNumber,
            "updatedAt" to FieldValue.serverTimestamp()
        ))
        return ref.id
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    suspend fun fetchAll(uid: String): List<BinderPageDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            BinderPageDocument(
                firestoreId = doc.id,
                binderFirestoreId = doc.getString("binderFirestoreId") ?: return@mapNotNull null,
                pageNumber = (doc.getLong("pageNumber") ?: return@mapNotNull null).toInt()
            )
        }
}

data class BinderPageDocument(val firestoreId: String, val binderFirestoreId: String, val pageNumber: Int)
```

#### 5. `CapPositionFirestoreService.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/CapPositionFirestoreService.kt`

**Intent**: Operacje CRUD w kolekcji `users/{uid}/cap_positions`. Dokumenty zawierają `binderPageFirestoreId`.

**Contract**:
```kotlin
@Singleton
class CapPositionFirestoreService @Inject constructor(private val firestore: FirebaseFirestore) {
    private fun col(uid: String) = firestore.collection("users/$uid/cap_positions")

    fun scheduleCreate(uid: String, binderPageFirestoreId: String, position: Int, capId: Long): String {
        val ref = col(uid).document()
        ref.set(mapOf(
            "binderPageFirestoreId" to binderPageFirestoreId,
            "position" to position,
            "capId" to capId,
            "updatedAt" to FieldValue.serverTimestamp()
        ))
        return ref.id
    }

    fun scheduleDelete(uid: String, firestoreId: String) {
        col(uid).document(firestoreId).delete()
    }

    fun scheduleDeleteByPage(uid: String, pageFirestoreId: String) {
        col(uid).whereEqualTo("binderPageFirestoreId", pageFirestoreId).get()
            .addOnSuccessListener { snap -> snap.documents.forEach { it.reference.delete() } }
    }

    suspend fun fetchAll(uid: String): List<CapPositionDocument> =
        col(uid).get().await().documents.mapNotNull { doc ->
            CapPositionDocument(
                firestoreId = doc.id,
                binderPageFirestoreId = doc.getString("binderPageFirestoreId") ?: return@mapNotNull null,
                position = (doc.getLong("position") ?: return@mapNotNull null).toInt(),
                capId = doc.getLong("capId") ?: return@mapNotNull null
            )
        }
}

data class CapPositionDocument(val firestoreId: String, val binderPageFirestoreId: String, val position: Int, val capId: Long)
```

#### 6. `BinderRepository.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/BinderRepository.kt`

**Intent**: Rozszerz o write-through do Firestore. `create` generuje Firestore ID przed zapisem do Room.
`delete` kaskaduje: usuwa strony z Firestore (nie ma pozycji — sprawdzone przez `check(occupied == 0)`),
potem usuwa klaser z Firestore, potem z Room.

**Contract**: Dodaj do konstruktora `BinderFirestoreService` i `BinderPageFirestoreService` i `FirebaseAuthManager`.
W `create()`: pobierz `uid` z authManager; jeśli not-null wywołaj `binderFirestoreService.scheduleCreate(uid, name)` → zapisz wynikowy `firestoreId` do `Binder(firestoreId = ...)`.
W `delete()`: przed `binderDao.deleteById()` pobierz strony przez `binderPageDao.getByBinderId(binderId).first()`, dla każdej `page.firestoreId?.let { binderPageFirestoreService.scheduleDelete(uid, it) }`, potem `binder.firestoreId?.let { binderFirestoreService.scheduleDelete(uid, it) }`.

#### 7. `BinderPageRepository.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/BinderPageRepository.kt`

**Intent**: Rozszerz o write-through. `addPage` wymaga `firestoreId` rodzica-Bindera.
`deletePage` kaskaduje usunięcie pozycji z Firestore przed usunięciem strony.

**Contract**: Dodaj `BinderDao`, `CapPositionDao`, `BinderPageFirestoreService`, `CapPositionFirestoreService`, `FirebaseAuthManager` do konstruktora.
W `addPage(binderId)`: `val binder = binderDao.getById(binderId)` → `binderPageFirestoreService.scheduleCreate(uid, binder.firestoreId!!, pageNumber)` → zapisz `firestoreId` do `BinderPage`.
W `deletePage(pageId)`: pobierz pozycje `capPositionDao.getByPage(pageId).first()` → `scheduleDelete` każdej pozycji → `scheduleDelete` strony → `binderPageDao.deleteById(pageId)`.

#### 8. `CapPositionRepository.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/CapPositionRepository.kt`

**Intent**: Rozszerz o write-through. `assign` i `reassign` zapisują do Firestore z `firestoreId` rodzica-strony.

**Contract**: Dodaj `BinderPageDao`, `CapPositionFirestoreService`, `FirebaseAuthManager` do konstruktora.
W `assign(binderPageId, position, capId)`: `val page = binderPageDao.getById(binderPageId)` → `scheduleCreate(uid, page.firestoreId!!, position, capId)` → zapisz do `CapPosition`.
W `unassign(capId)`: `val pos = dao.getByCapId(capId)` → `pos.firestoreId?.let { scheduleDelete(uid, it) }` → `dao.deleteByCapId(capId)`.
W `reassign(capId, newBinderPageId, newPosition)`: sprawdź `newPosition in 1..35`; stary wpis usuń z Firestore; nowy dodaj z nowym `firestoreId`.

#### 9. `FirestoreWriteThroughTest.kt` (nowy plik)

**File**: `app/src/androidTest/java/pl/sroki/cci/android/data/FirestoreWriteThroughTest.kt`

**Intent**: Weryfikuje że po create Bindera firestoreId jest non-null w Room. Używa Firestore Emulator
(`FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)`).

**Contract**: `@Before` — uruchom emulator connection; `@Test fun createBinder_setsFirestoreId()` —
utwórz Binder przez `BinderRepository.create()`, sprawdź `binderDao.getById(id)?.firestoreId != null`.

### Success Criteria:

#### Automated Verification:

- Kompilacja: `./gradlew :app:compileDebugKotlin`
- FirestoreWriteThroughTest (emulator): `./gradlew :app:connectedDebugAndroidTest`
- Ktlint: `./gradlew :app:ktlintCheck`

#### Manual Verification:

- Firebase Console: utwórz klaser w aplikacji → dokument pojawia się pod `users/{uid}/binders`
- Firebase Console: usuń klaser → dokument oraz strony usunięte z Firestore
- Brak sieci: utwórz klaser → zapis do Room OK → po przywróceniu sieci dokument pojawia się w Firestore

---

## Phase 3: Initial pull / restore

### Overview

`FirestoreRestoreUseCase` sprawdza przy starcie aplikacji czy Room jest puste i czy Firestore ma dane
dla bieżącego UID. Jeśli tak — odtwarza hierarchię Binder → BinderPage → CapPosition, budując mapę
`firestoreId → Room Long id` do poprawnego wiązania FK.

### Changes Required:

#### 1. `FirestoreRestoreUseCase.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt`

**Intent**: Encapsuluje logikę jednorazowego odtwarzania danych z Firestore do pustej bazy Room.
Wywołane przez CCIApplication.onCreate() — nie przez UI.

**Contract**:
```kotlin
@Singleton
class FirestoreRestoreUseCase @Inject constructor(
    private val authManager: FirebaseAuthManager,
    private val binderDao: BinderDao,
    private val binderPageDao: BinderPageDao,
    private val capPositionDao: CapPositionDao,
    private val binderService: BinderFirestoreService,
    private val binderPageService: BinderPageFirestoreService,
    private val capPositionService: CapPositionFirestoreService
) {
    suspend fun restoreIfEmpty() {
        val uid = authManager.uid.value ?: return
        if (binderDao.countAll() > 0) return  // Room ma dane — skip
        val fsIdToRoomId = mutableMapOf<String, Long>()
        binderService.fetchAll(uid).forEach { doc ->
            val id = binderDao.insert(Binder(name = doc.name, firestoreId = doc.firestoreId))
            fsIdToRoomId[doc.firestoreId] = id
        }
        val pageIdToRoomId = mutableMapOf<String, Long>()
        binderPageService.fetchAll(uid).forEach { doc ->
            val parentRoomId = fsIdToRoomId[doc.binderFirestoreId] ?: return@forEach
            val id = binderPageDao.insert(
                BinderPage(binderId = parentRoomId, pageNumber = doc.pageNumber, firestoreId = doc.firestoreId)
            )
            pageIdToRoomId[doc.firestoreId] = id
        }
        capPositionService.fetchAll(uid).forEach { doc ->
            val parentRoomId = pageIdToRoomId[doc.binderPageFirestoreId] ?: return@forEach
            capPositionDao.insert(
                CapPosition(binderPageId = parentRoomId, position = doc.position, capId = doc.capId,
                    firestoreId = doc.firestoreId)
            )
        }
    }
}
```
Wymaga dodania `@Query("SELECT COUNT(*) FROM binder") suspend fun countAll(): Int` do `BinderDao`.

#### 2. `BinderDao.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/BinderDao.kt`

**Intent**: Dodaj `countAll()` — używane przez `FirestoreRestoreUseCase` do sprawdzenia czy Room puste.

**Contract**: `@Query("SELECT COUNT(*) FROM binder") suspend fun countAll(): Int`

#### 3. `CCIApplication.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/CCIApplication.kt`

**Intent**: Na starcie aplikacji zapewnij zalogowanie Firebase Anonymous + uruchom restore jeśli potrzebny.

**Contract**:
```kotlin
@HiltAndroidApp
class CCIApplication : Application() {
    @Inject lateinit var firebaseAuthManager: FirebaseAuthManager
    @Inject lateinit var firestoreRestoreUseCase: FirestoreRestoreUseCase

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            firebaseAuthManager.ensureSignedIn()
            firestoreRestoreUseCase.restoreIfEmpty()
        }
    }
}
```

#### 4. `FirestoreRestoreTest.kt` (nowy plik)

**File**: `app/src/androidTest/java/pl/sroki/cci/android/data/FirestoreRestoreTest.kt`

**Intent**: Weryfikuje że `restoreIfEmpty` odtwarza pełną hierarchię binder→page→position z emulatora do Room.

**Contract**: `@Before` — zapisz do Firestore Emulator: 1 Binder (z firestoreId), 1 BinderPage (z binderFirestoreId), 1 CapPosition (z binderPageFirestoreId).
`@Test fun restoreIfEmpty_rebuildsHierarchy()` — wyczyść Room; wywołaj `restoreIfEmpty()`; sprawdź Room: 1 binder, 1 binder_page, 1 cap_position; FK poprawne.

### Success Criteria:

#### Automated Verification:

- FirestoreRestoreTest (emulator): `./gradlew :app:connectedDebugAndroidTest`
- Kompilacja: `./gradlew :app:compileDebugKotlin`
- Ktlint: `./gradlew :app:ktlintCheck`

#### Manual Verification:

- Wyczyść dane aplikacji → uruchom ponownie → dane z Firestore odtworzone w Room
  (weryfikacja: Android Studio App Inspection → binder, binder_page, cap_position mają wpisy)
- Aplikacja uruchamia się bez ANR/crasha gdy Firestore Restore trwa w tle

---

## Testing Strategy

### Instrumented Tests (Emulator):

- `MigrationTest` — Room migration 1→2: binder/binder_page/cap_position mają `firestore_id`
- `FirestoreWriteThroughTest` — create/delete zapisuje do emulatora; `firestoreId` w Room non-null
- `FirestoreRestoreTest` — Firestore → Room restore odtwarza pełną hierarchię

### Manual Testing Steps:

1. Phase 1: uruchom app po raz pierwszy → Firebase Console → anonimowy użytkownik pojawia się
2. Phase 2: utwórz klaser w UI → Firebase Console → widoczny pod `users/{uid}/binders`
3. Phase 2: dodaj stronę → `users/{uid}/binder_pages` rośnie; usuń stronę → znika
4. Phase 3: wyczyść dane app → uruchom → sprawdź App Inspection czy Room odtworzone

## Migration Notes

Room migration 1→2: `firestoreId = NULL` dla wszystkich istniejących rekordów. Istniejące dane
w Room nie będą zsynchronizowane z Firestore (no backfill). Backfill jest nietrywialny i poza scope MVP —
użytkownik może ręcznie odtworzyć dane lub zaakceptować że stare dane nie mają backup do czasu edycji.

## Performance Considerations

- Write-through: Firestore SDK lokalizuje zapis natychmiastowo; sieciowy RTT nie blokuje UI (fire-and-forget)
- Restore: sekwencyjne 3 zapytania Firestore przy starcie — akceptowalne (jednorazowe, dane prywatne)
- `BinderDao.countAll()` jest O(1) — zero overhead przy każdym starcie gdy Room ma dane

## References

- Room migration: `data/datasource/local/CciDatabase.kt`
- Wzorzec modułu Hilt: `di/DatabaseModule.kt`
- Wzorzec repozytorium: `data/BinderRepository.kt`
- Roadmap F-03: `context/foundation/roadmap.md`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Firebase infra + Room migration v1→v2

#### Automated

- [x] 1.1 Kompilacja: `./gradlew :app:compileDebugKotlin`
- [x] 1.2 KSP: `./gradlew :app:kspDebugKotlin`
- [x] 1.3 MigrationTest: `./gradlew :app:connectedDebugAndroidTest`
- [x] 1.4 Ktlint: `./gradlew :app:ktlintCheck`

#### Manual

- [x] 1.5 Aplikacja uruchamia się bez crasha — Logcat czysty
- [x] 1.6 Firebase Console → anonimowy użytkownik pojawia się po pierwszym uruchomieniu

### Phase 2: Write-through sync

#### Automated

- [ ] 2.1 Kompilacja: `./gradlew :app:compileDebugKotlin`
- [ ] 2.2 FirestoreWriteThroughTest (emulator): `./gradlew :app:connectedDebugAndroidTest`
- [ ] 2.3 Ktlint: `./gradlew :app:ktlintCheck`

#### Manual

- [ ] 2.4 Firebase Console: utwórz klaser → dokument w `users/{uid}/binders`
- [ ] 2.5 Firebase Console: usuń klaser → dokument usunięty z Firestore

### Phase 3: Initial pull / restore

#### Automated

- [ ] 3.1 FirestoreRestoreTest (emulator): `./gradlew :app:connectedDebugAndroidTest`
- [ ] 3.2 Kompilacja: `./gradlew :app:compileDebugKotlin`
- [ ] 3.3 Ktlint: `./gradlew :app:ktlintCheck`

#### Manual

- [ ] 3.4 Wyczyść dane app → uruchom → Room odtworzone z Firestore (App Inspection)
- [ ] 3.5 Brak ANR/crasha podczas restore w tle

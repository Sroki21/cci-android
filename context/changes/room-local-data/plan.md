# Room Local Data Layer — Plan implementacji

## Overview

Dodanie lokalnej bazy danych Room z 4 encjami: `PendingCap` (kapsle oczekujące na skatalogowanie),
`Binder` + `BinderPage` (klasery i ich strony), `CapPosition` (przypisanie kapsla do slotu w klaserze).
Warstwa F-02 odblokowuje S-01 (shop-check) i S-02 (binder-management).

## Current State Analysis

Brak Room w projekcie — wszystkie dane przychodzą z Retrofit API. `build.gradle` nie zawiera
żadnych zależności `androidx.room`. KSP jest skonfigurowane (używane przez Hilt), więc
`ksp "androidx.room:room-compiler"` wchodzi bez dodatkowej konfiguracji procesora.

Wzorzec DI: jeden `@Module @InstallIn(SingletonComponent::class) object` per temat —
`di/NetworkModule.kt` tworzy cały stos sieciowy. Tworzymy analogiczny `di/DatabaseModule.kt`.

Repozytoria: `@Singleton class XxxRepository @Inject constructor(private val dao: XxxDao)` —
dokładnie tak samo jak istniejące repozytoria wstrzykują serwisy Retrofit.

### Key Discoveries

- `app/build.gradle` — brak Room; KSP już obecne: `ksp "com.google.dagger:hilt-android-compiler:2.59.2"`
- `di/NetworkModule.kt:28` — wzorzec modułu Hilt do powielenia w `DatabaseModule.kt`
- `data/CapsRepository.kt` — wzorzec `@Inject constructor(private val api: CapApiService)` dla repozytoriów
- `model/Cap.kt` — `id: Long` → `PendingCap.cap_id: Long` (ten sam typ)
- Testy instrumentowane: `app/src/androidTest/java/pl/sroki/cci/android/` — tu trafiają testy Room in-memory

## Desired End State

1. `Room.databaseBuilder(..., "cci.db")` z 4 tabelami dostępny przez Hilt w całej aplikacji.
2. `PendingCapRepository.add(capId)` / `remove(capId)` — idempotentne operacje na liście oczekujących.
3. `BinderRepository.create(name)` / `delete(binderId)` — tworzenie i chronione usuwanie klaserów.
4. `BinderPageRepository.addPage(binderId)` — dodaje kolejną stronę (max 15), rzuca wyjątek przy przekroczeniu.
5. `CapPositionRepository.assign(...)` — zajmuje slot (binder_page_id, position) unikalnie; waliduje position 1–35.
6. Testy in-memory pokrywają: CRUD, unique constraints, walidację limitów, kaskadowe usuwanie.

Weryfikacja: `./gradlew :app:kspDebugKotlin` (Hilt+Room codegen bez błędów) +
`./gradlew :app:connectedDebugAndroidTest` na emulatorze (testy in-memory Room).

### Key Discoveries

- `ForeignKey.CASCADE` na BinderPage→Binder i CapPosition→BinderPage — usunięcie klasera/strony kaskadowo usuwa dzieci
- Ochrona "klaser z kapslami nie może być usunięty" egzekwowana w `BinderRepository` (sprawdzenie CapPositionDao przed `delete`) — DB constraint celowo pominięty (Room nie obsługuje `CHECK` natively w adnotacjach)
- PendingCap.capId jest PK — insert z `OnConflictStrategy.IGNORE` zapewnia idempotentność `add()`

## What We're NOT Doing

- S-01 (oznaczanie kapsla jako kupionego — UI i API `POST /collection`) — to S-01
- S-02 (UI zarządzania klaserami) — to S-02
- Migracje Room z SQL — używamy `fallbackToDestructiveMigration()` do czasu pierwszego release
- Room `TypeConverters` — wszystkie pola to typy prymitywne, konwertery niepotrzebne
- Cachowanie metadanych kapsla (name, imageUrl) lokalnie — tylko ID-ki; dane wyświetlane z API
- Eksport / backup bazy danych

## Implementation Approach

Jedna faza: zależności → encje → DAO-y → `CciDatabase` → `DatabaseModule` →
repozytoria z walidacją → testy in-memory. Kolejność buduje od dołu (schemat)
ku górze (logika domenowa), umożliwiając weryfikację kompilacji Hilt/Room
zanim dojdziemy do logiki repozytoriów.

## Critical Implementation Details

**`@Index` wymagany przy każdym FK w encji Room**: Room generuje ostrzeżenie (traktowane jako error
przez ktlint/strict mode) gdy kolumna FK nie ma indeksu. Każda kolumna `binder_id` i `binder_page_id`
musi mieć `@Index` obok unique composite index.

**`fallbackToDestructiveMigration()` kasuje dane przy każdej zmianie schematu**: w fazach development
to pożądane, ale oznacza że w androidTest musi być użyte `Room.inMemoryDatabaseBuilder` — nie
`databaseBuilder` z plikiem — żeby nie interferować z danymi deweloperskimi na urządzeniu testowym.

---

## Phase 1: Room local data layer

### Overview

Pełna warstwa lokalna: zależności Gradle → 4 encje Room → 4 DAO-y →
`CciDatabase` → `DatabaseModule` (Hilt) → 4 repozytoria z walidacją →
testy in-memory w `androidTest/`.

### Changes Required

#### 1. Room dependencies

**File**: `app/build.gradle`

**Intent**: Dodać Room runtime, ktx i compiler (KSP) do zależności aplikacji,
oraz room-testing do androidTest.

**Contract**:
```groovy
def room_version = "2.7.0"
implementation "androidx.room:room-runtime:$room_version"
implementation "androidx.room:room-ktx:$room_version"
ksp "androidx.room:room-compiler:$room_version"
androidTestImplementation "androidx.room:room-testing:$room_version"
```

#### 2. PendingCap entity

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/PendingCap.kt`

**Intent**: Encja Room przechowująca ID kapsla oczekującego na skatalogowanie.
Wstawiona przy S-01 "mark as bought", usuwana przy S-03 "skataloguj do klasera".

**Contract**: `@Entity(tableName = "pending_cap") data class PendingCap(@PrimaryKey val capId: Long)`

#### 3. Binder entity

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/Binder.kt`

**Intent**: Encja klasera. Użytkownik tworzy klasery z nazwą (wolna forma,
np. "Europa 1"). Klaser może mieć do 15 stron.

**Contract**: `@Entity(tableName = "binder") data class Binder(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)`

#### 4. BinderPage entity

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/BinderPage.kt`

**Intent**: Encja strony w klaserze. Numer strony 1–15 walidowany w Repository.
FK do Binder z CASCADE — usunięcie klasera usuwa wszystkie jego strony.

**Contract**:
```kotlin
@Entity(
    tableName = "binder_page",
    foreignKeys = [ForeignKey(
        entity = Binder::class,
        parentColumns = ["id"],
        childColumns = ["binder_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["binder_id", "page_number"], unique = true),
        Index("binder_id")
    ]
)
data class BinderPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "binder_id") val binderId: Long,
    @ColumnInfo(name = "page_number") val pageNumber: Int
)
```

#### 5. CapPosition entity

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/CapPosition.kt`

**Intent**: Encja przypisania kapsla do slotu (strona, pozycja 1–35).
FK do BinderPage z CASCADE. Unique constraint na (binder_page_id, position)
egzekwowany przez DB — duplikat rzuca `SQLiteConstraintException`.

**Contract**:
```kotlin
@Entity(
    tableName = "cap_position",
    foreignKeys = [ForeignKey(
        entity = BinderPage::class,
        parentColumns = ["id"],
        childColumns = ["binder_page_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["binder_page_id", "position"], unique = true),
        Index("binder_page_id")
    ]
)
data class CapPosition(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "binder_page_id") val binderPageId: Long,
    val position: Int,
    @ColumnInfo(name = "cap_id") val capId: Long
)
```

#### 6. PendingCapDao

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/PendingCapDao.kt`

**Intent**: DAO dla tabeli pending_cap. Reaktywna lista przez Flow, operacje mutujące suspend.

**Contract**:
```kotlin
@Dao interface PendingCapDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cap: PendingCap): Long
    @Query("DELETE FROM pending_cap WHERE cap_id = :capId")
    suspend fun deleteById(capId: Long)
    @Query("SELECT * FROM pending_cap")
    fun getAll(): Flow<List<PendingCap>>
    @Query("SELECT COUNT(*) FROM pending_cap WHERE cap_id = :capId")
    suspend fun exists(capId: Long): Int
}
```

#### 7. BinderDao

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/BinderDao.kt`

**Intent**: DAO dla tabeli binder. Zwraca reaktywną listę klaserów, obsługuje insert i delete.

**Contract**:
```kotlin
@Dao interface BinderDao {
    @Insert suspend fun insert(binder: Binder): Long
    @Query("DELETE FROM binder WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT * FROM binder ORDER BY name")
    fun getAll(): Flow<List<Binder>>
    @Query("SELECT * FROM binder WHERE id = :id")
    suspend fun getById(id: Long): Binder?
}
```

#### 8. BinderPageDao

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/BinderPageDao.kt`

**Intent**: DAO dla tabeli binder_page. Kluczowe: `countByBinderId` używane przez Repository
do egzekwowania limitu 15 stron przed insertowaniem.

**Contract**:
```kotlin
@Dao interface BinderPageDao {
    @Insert suspend fun insert(page: BinderPage): Long
    @Query("DELETE FROM binder_page WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT * FROM binder_page WHERE binder_id = :binderId ORDER BY page_number")
    fun getByBinderId(binderId: Long): Flow<List<BinderPage>>
    @Query("SELECT COUNT(*) FROM binder_page WHERE binder_id = :binderId")
    suspend fun countByBinderId(binderId: Long): Int
}
```

#### 9. CapPositionDao

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/CapPositionDao.kt`

**Intent**: DAO dla tabeli cap_position. `countByBinderId` używany przez `BinderRepository`
do sprawdzenia czy klaser jest zajęty przed usunięciem.

**Contract**:
```kotlin
@Dao interface CapPositionDao {
    @Insert suspend fun insert(pos: CapPosition): Long
    @Delete suspend fun delete(pos: CapPosition)
    @Update suspend fun update(pos: CapPosition)
    @Query("SELECT * FROM cap_position WHERE binder_page_id = :binderPageId")
    fun getByPage(binderPageId: Long): Flow<List<CapPosition>>
    @Query("SELECT * FROM cap_position WHERE cap_id = :capId LIMIT 1")
    suspend fun getByCapId(capId: Long): CapPosition?
    @Query("DELETE FROM cap_position WHERE cap_id = :capId")
    suspend fun deleteByCapId(capId: Long)
    @Query("""
        SELECT COUNT(*) FROM cap_position
        WHERE binder_page_id IN (SELECT id FROM binder_page WHERE binder_id = :binderId)
    """)
    suspend fun countByBinderId(binderId: Long): Int
}
```

#### 10. CciDatabase

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/CciDatabase.kt`

**Intent**: Główna klasa bazy danych Room — rejestruje wszystkie 4 encje,
eksponuje DAO-y jako abstrakcyjne funkcje.

**Contract**:
```kotlin
@Database(
    entities = [PendingCap::class, Binder::class, BinderPage::class, CapPosition::class],
    version = 1
)
abstract class CciDatabase : RoomDatabase() {
    abstract fun pendingCapDao(): PendingCapDao
    abstract fun binderDao(): BinderDao
    abstract fun binderPageDao(): BinderPageDao
    abstract fun capPositionDao(): CapPositionDao
}
```

#### 11. DatabaseModule

**File**: `app/src/main/java/pl/sroki/cci/android/di/DatabaseModule.kt`

**Intent**: Hilt module udostępniający `CciDatabase` i wszystkie DAO-y jako
`@Singleton @Provides`. Analogiczny do `NetworkModule.kt`.

**Contract**:
- `provideDatabase(@ApplicationContext context: Context): CciDatabase` — `@Singleton`; `Room.databaseBuilder(..., "cci.db").fallbackToDestructiveMigration().build()`
- Cztery `@Provides` (bez `@Singleton`) zwracające DAO przez `db.xxxDao()`:
  `providePendingCapDao`, `provideBinderDao`, `provideBinderPageDao`, `provideCapPositionDao`

#### 12. PendingCapRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/PendingCapRepository.kt`

**Intent**: Singleton zarządzający listą kapsli oczekujących. Operacja `add` jest
idempotentna (IgnoreConflict w DAO). Eksponuje reaktywną listę ID-ków.

**Contract**:
```kotlin
@Singleton
class PendingCapRepository @Inject constructor(private val dao: PendingCapDao) {
    fun getAll(): Flow<List<Long>>   // mapuje Flow<List<PendingCap>> → Flow<List<Long>>
    suspend fun add(capId: Long)
    suspend fun remove(capId: Long)
}
```

#### 13. BinderRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/BinderRepository.kt`

**Intent**: Singleton dla CRUD klaserów. `delete` sprawdza czy klaser zawiera kapsle
(przez `CapPositionDao.countByBinderId`) — jeśli tak, rzuca `IllegalStateException`.

**Contract**:
```kotlin
@Singleton
class BinderRepository @Inject constructor(
    private val binderDao: BinderDao,
    private val capPositionDao: CapPositionDao
) {
    fun getAll(): Flow<List<Binder>>
    suspend fun create(name: String): Long   // require(name.isNotBlank())
    suspend fun delete(binderId: Long)       // throws IllegalStateException gdy countByBinderId > 0
}
```

#### 14. BinderPageRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/BinderPageRepository.kt`

**Intent**: Singleton dla stron w klaserze. `addPage` auto-przydziela `pageNumber`
(aktualny count + 1), sprawdza limit 15, rzuca `IllegalStateException` przy przekroczeniu.

**Contract**:
```kotlin
@Singleton
class BinderPageRepository @Inject constructor(private val dao: BinderPageDao) {
    fun getByBinder(binderId: Long): Flow<List<BinderPage>>
    suspend fun addPage(binderId: Long): Long   // count < 15 else throw; pageNumber = count + 1
    suspend fun deletePage(pageId: Long)
}
```

#### 15. CapPositionRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/CapPositionRepository.kt`

**Intent**: Singleton dla slotów w klaserze. `assign` waliduje position 1–35.
`reassign` atomowo przesuwa kapsel (delete + insert w jednej transakcji).
`assign` propaguje `SQLiteConstraintException` gdy slot zajęty — wywołujący (S-03 UI) obsługuje błąd.

**Contract**:
```kotlin
@Singleton
class CapPositionRepository @Inject constructor(private val dao: CapPositionDao) {
    fun getByPage(binderPageId: Long): Flow<List<CapPosition>>
    suspend fun getByCapId(capId: Long): CapPosition?
    suspend fun assign(binderPageId: Long, position: Int, capId: Long): Long
        // require(position in 1..35); dao.insert(CapPosition(...))
    @Transaction
    suspend fun reassign(capId: Long, newBinderPageId: Long, newPosition: Int)
        // dao.deleteByCapId(capId); assign(newBinderPageId, newPosition, capId)
    suspend fun unassign(capId: Long)
}
```

#### 16. Testy in-memory — DAO i walidacja Repository

**Files**:
- `app/src/androidTest/java/pl/sroki/cci/android/data/PendingCapDaoTest.kt`
- `app/src/androidTest/java/pl/sroki/cci/android/data/BinderRepositoryTest.kt`
- `app/src/androidTest/java/pl/sroki/cci/android/data/CapPositionRepositoryTest.kt`

**Intent**: Testy instrumentowane (device/emulator) używające `Room.inMemoryDatabaseBuilder`.
Weryfikują: CRUD, unique constraint na slocie (expect wyjątek przy duplikacie),
limit 15 stron, ochronę klasera z kapslami, walidację position 1–35.

**Contract**:
- Setup: `@Before fun createDb() { db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java).allowMainThreadQueries().build() }`
- Używaj `runBlocking` lub `runTest` dla suspend functions w testach
- Po każdym teście: `@After fun closeDb() { db.close() }`
- `PendingCapDaoTest`: insert, idempotent insert (IGNORE), delete, getAll (Flow jako lista przez `first()`)
- `BinderRepositoryTest`: create, delete empty binder, delete occupied binder (expect `IllegalStateException`)
- `CapPositionRepositoryTest`: assign, assign duplicate slot (expect `SQLiteConstraintException`), position out of range (expect `IllegalArgumentException`), reassign

### Success Criteria

#### Automated Verification

- Projekt kompiluje się bez błędów: `./gradlew :app:compileDebugKotlin`
- Hilt + Room codegen bez błędów: `./gradlew :app:kspDebugKotlin`
- Testy in-memory przechodzą: `./gradlew :app:connectedDebugAndroidTest`
- Lint przechodzi: `./gradlew :app:ktlintCheck`

#### Manual Verification

- Aplikacja uruchamia się bez crash — Hilt prawidłowo tworzy `CciDatabase` (sprawdź Logcat: brak `DaggerError`)
- `CciDatabase` widoczny w Android Studio App Inspection (Database Inspector) po zimnym starcie

**Implementation Note**: Testy in-memory wymagają podłączonego emulatora lub urządzenia.
Uruchom `./gradlew :app:connectedDebugAndroidTest` po podłączeniu urządzenia / uruchomieniu emulatora.

---

## Testing Strategy

### Instrumented Tests (in-memory Room)

- `PendingCapDaoTest` — 4 scenariusze: insert, idempotent add, delete, reactive getAll
- `BinderRepositoryTest` — 3 scenariusze: create, delete empty, delete occupied (wyjątek)
- `CapPositionRepositoryTest` — 5 scenariuszy: assign, duplicate slot, invalid position, reassign, unassign

### Unit Tests (host JVM)

Repozytoria mają prostą logikę walidacji — testowana pośrednio przez testy instrumentowane.
Oddzielne unit testy mockujące DAO nie wnoszą wartości ponad in-memory testy, które testują
rzeczywisty SQL. Pomijamy.

### Manual Testing Steps

1. Zimny start aplikacji — sprawdź Logcat: brak błędów Hilt ani Room
2. Android Studio → App Inspection → Database Inspector: widać `cci.db` z 4 tabelami
3. Wywołaj tymczasowo `PendingCapRepository.add(1L)` z dowolnego ViewModelu — w inspektorze
   pojawia się wiersz w `pending_cap`

## Migration Notes

`fallbackToDestructiveMigration()` — baza kasowana i tworzona od nowa przy każdej zmianie
schematu Room. Akceptowalne przez cały development pre-release. Przy pierwszym release
wymagane będzie zastąpienie to przez `addMigrations(MIGRATION_1_2, ...)`.

## References

- Roadmap F-02: `context/foundation/roadmap.md`
- Wzorzec DI: `app/src/main/java/pl/sroki/cci/android/di/NetworkModule.kt:28`
- Wzorzec repozytorium: `app/src/main/java/pl/sroki/cci/android/data/CapsRepository.kt`
- Room docs: https://developer.android.com/training/data-storage/room
- Encje odblokowane: S-01 (`PendingCap`), S-02 (`Binder`, `BinderPage`), S-03/S-04 (`CapPosition`)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Room local data layer

#### Automated

- [ ] 1.1 Projekt kompiluje się bez błędów: `./gradlew :app:compileDebugKotlin`
- [ ] 1.2 Hilt + Room codegen bez błędów: `./gradlew :app:kspDebugKotlin`
- [ ] 1.3 Testy in-memory przechodzą: `./gradlew :app:connectedDebugAndroidTest`
- [ ] 1.4 Lint przechodzi: `./gradlew :app:ktlintCheck`

#### Manual

- [ ] 1.5 Aplikacja uruchamia się bez crash — Hilt tworzy CciDatabase (Logcat czysty)
- [ ] 1.6 CciDatabase widoczny w Android Studio App Inspection z 4 tabelami

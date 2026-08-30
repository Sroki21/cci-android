---
title: "CCI Android — Anti-Corruption Layer: przeciek Room entities do UI"
created: 2026-06-16
type: refactor-plan
sources:
  - context/foundation/prd.md (v1)
  - context/domain/01-domain-distillation.md
  - context/domain/02-invariant-aggregate-refactor.md
  - app/src/main/java/pl/sroki/cci/android/ (kod źródłowy)
---

# CCI Android — Plan refaktoru: Anti-Corruption Layer dla encji Room

> **STATUS: WDROŻONY.** Ten dokument opisuje obowiązujący kształt granicy między Roomem a UI,
> nie zadanie do wykonania. Kryterium z planu jest spełnione — `grep -r "datasource.local.entity"
> ui/` daje zero trafień. Dwa siostrzane dokumenty z tej samej analizy (destylacja domeny
> i porzucony agregat `CollectionEntry`) leżą w `context/archive/2026-06-16-domain-analysis/`.

---

## KROK 0 — Odkryj kontekst

### Dokumenty bazowe

| Dokument | Rola |
|----------|------|
| `context/foundation/prd.md:141` | "Dane struktury klaserów przechowywane **wyłącznie na urządzeniu**" — implicit deklaracja, że warstwa persystencji jest szczegółem implementacyjnym |
| `context/domain/01-domain-distillation.md` | Destylacja domeny — Core = Binder/CollectionEntry; Generic = Room/Firestore |
| `context/domain/02-invariant-aggregate-refactor.md` | Plan CollectionRepository — trójstanowy model posiadania |

### Stack i warstwy

```
Android / Kotlin 2.3.20

Warstwy (od najbardziej wewnętrznej do zewnętrznej):
  data/datasource/local/entity/   ← Encje Room (@Entity, @PrimaryKey, @ForeignKey)
  data/datasource/local/dao/      ← DAOs (@Dao, @Query)
  data/datasource/local/          ← CciDatabase (@Database)
  data/*Repository.kt             ← Repozytoria per domena
  ui/.../ViewModel.kt             ← @HiltViewModel
  ui/.../*Screen.kt / *View.kt    ← @Composable (warstwa UI)
```

**Zależności zewnętrzne (build.gradle):**
- `androidx.room:room-runtime:2.7.0` — ORM dla SQLite (lokalna baza)
- `com.google.firebase:firebase-firestore-ktx` — cloud backup
- `com.squareup.retrofit2:retrofit:3.0.0` — HTTP client
- `kotlinx.serialization:json:1.11.0` — serializacja JSON
- `io.sentry:sentry-android:7.22.6` — monitoring błędów

---

## KROK 1 — Identyfikacja przeciekających zależności

### Zależność A: `androidx.room.*` — wszystkie pliki które ją "znają"

**Poprawne lokalizacje (oczekiwane):**

| Plik | Import Room | Rola |
|------|-------------|------|
| `data/datasource/local/entity/Binder.kt:3-5` | `@Entity @PrimaryKey @ColumnInfo` | Definicja encji — własne miejsce |
| `data/datasource/local/entity/BinderPage.kt:3-7` | `@Entity @ForeignKey @Index @PrimaryKey` | Definicja encji — własne miejsce |
| `data/datasource/local/entity/CapPosition.kt:3-7` | `@Entity @ForeignKey @Index @PrimaryKey` | Definicja encji — własne miejsce |
| `data/datasource/local/entity/CapCache.kt:3-5` | `@Entity @PrimaryKey @ColumnInfo` | Definicja encji — własne miejsce |
| `data/datasource/local/entity/PendingCap.kt:3-5` | `@Entity @PrimaryKey @ColumnInfo` | Definicja encji — własne miejsce |
| `data/datasource/local/dao/CapPositionDao.kt:3-9` | `@Dao @Insert @Delete @Update @Query` | DAO — własne miejsce |
| `data/datasource/local/dao/BinderDao.kt:3-5` | `@Dao @Insert @Query` | DAO — własne miejsce |
| `data/datasource/local/dao/BinderPageDao.kt:3-5` | `@Dao @Insert @Query` | DAO — własne miejsce |
| `data/datasource/local/dao/CapCacheDao.kt:3-4` | `@Dao @Query` | DAO — własne miejsce |
| `data/datasource/local/dao/PendingCapDao.kt:3-6` | `@Dao @Insert @Query` | DAO — własne miejsce |
| `data/datasource/local/CciDatabase.kt:3-18` | `@Database @RoomDatabase Migration` | Definicja bazy — własne miejsce |
| `di/DatabaseModule.kt:4` | `Room` (builder) | Moduł DI — akceptowalne |

**Przekroczenia granicy — encje Room poza warstwą `data/`:**

| Plik | Import Room (linia) | Kontekst naruszenia |
|------|--------------------|--------------------|
| `ui/binders/BindersViewModel.kt:30` | `entity.CapCache` | ViewModel zna encję Room |
| `ui/binders/BindersViewModel.kt:31` | `entity.Binder` | ViewModel zna encję Room |
| `ui/binders/BindersViewModel.kt:32` | `entity.BinderPage` | ViewModel zna encję Room |
| `ui/binders/BindersViewModel.kt:33` | `entity.CapPosition` | ViewModel zna encję Room |
| `ui/binders/BindersScreen.kt:51` | `entity.Binder` | **Composable** zna encję Room |
| `ui/binders/BindersScreen.kt:52` | `entity.BinderPage` | **Composable** zna encję Room |
| `ui/binders/BindersScreen.kt:53` | `entity.CapPosition` | **Composable** zna encję Room |
| `ui/catalog/caps/detail/CapDetailView.kt:34` | `entity.Binder` | **Composable** zna encję Room |
| `ui/catalog/caps/detail/CapDetailView.kt:35` | `entity.BinderPage` | **Composable** zna encję Room |
| `ui/catalog/caps/detail/CapDetailViewModel.kt:23` | `entity.Binder` | ViewModel zna encję Room |
| `ui/catalog/caps/detail/CapDetailViewModel.kt:24` | `entity.BinderPage` | ViewModel zna encję Room |
| `ui/statistics/verification/CollectionVerificationViewModel.kt:15` | `entity.CapCache` | ViewModel zna encję Room |
| `ui/statistics/verification/CollectionVerificationScreen.kt:38` | `entity.CapCache` | **Composable** zna encję Room |

**Dodatkowe naruszenia w `data/model/` (Room w DTO warstwy danych):**

| Plik | Import Room | Problem |
|------|-------------|---------|
| `data/model/OwnedCapRow.kt:3` | `@ColumnInfo` | Projekcja DAO przechowuje adnotację Room w "modelu" |
| `data/model/CountryStatRow.kt:3` | `@ColumnInfo` | Projekcja DAO przechowuje adnotację Room w "modelu" |

### Zależność B: `kotlinx.serialization.*` — wszystkie pliki które ją "znają"

| Plik | Import | Kontekst |
|------|--------|----------|
| `model/Cap.kt:4` | `@Serializable` | Domenowy model kapsla MA adnotację serializacji |
| `model/CapExtended.kt:5` | `@Serializable` | Domenowy model kapsla MA adnotację serializacji |
| `model/IsInCollectionSerializer.kt:4-12` | `KSerializer JsonDecoder JsonPrimitive` | **Kod frameworku serializacji w warstwie domenowej** |
| `model/InstantSerializer.kt:3-7` | `KSerializer` | **Kod frameworku serializacji w warstwie domenowej** |
| `model/AdditionalImage.kt:3` | `@Serializable` | Domenowy model |
| `model/CapProperty.kt:3` | `@Serializable` | Domenowy model |
| `model/Category.kt:3` | `@Serializable` | Domenowy model |
| `model/GroupSign.kt:3` | `@Serializable` | i inne 8+ modeli domenowych... |
| `data/AuthRepository.kt:6` | `Json` | Repozatorium — akceptowalne |
| `data/SessionRepository.kt:9` | `Json` | Repozytorium — akceptowalne |
| `di/NetworkModule.kt:13` | `Json` | Moduł DI — akceptowalne |

---

## KROK 2 — Klasyfikacja i wybór #1

| Kandydat | (a) Warstwy/pliki dotknięte | (b) Ryzyko/koszt wymiany biblioteki | (c) Rozjazd intencja–kod | Priorytet |
|----------|-----------------------------|------------------------------------|--------------------------|-----------|
| **Room w UI (6 plików UI + 2 data/model)** | 4 warstwy: entity → dao → repo → ViewModel/Composable | **WYSOKI** — zamiana Room na SQLDelight/DataStore wymaga zmiany 13 plików UI | PRD:`prd.md:141` "wyłącznie na urządzeniu" = impl. szczegół; kod wystawia `BinderPage.firestoreId` bezpośrednio do widoku | **#1** |
| kotlinx.serialization w `model/` (15+ plików) | 2 warstwy: model/ + data/ | ŚREDNI — zmiana serializatora = zmiana adnotacji na domenowych modelach | CLAUDE.md jawnie dokumentuje jako intencjonalne ("nowe modele API trafiają tutaj z adnotacją `@Serializable`") | #2 |

### Wybrany przeciek #1: Room entities w UI

**Uzasadnienie:**

1. **Zasięg** — 4 warstwy przeniknięte: encja Room (schema DB) → DAO (zapytania SQL) → Repository → ViewModel/Composable (renderowanie). Room dotarł najdalej.

2. **Ryzyko wymiany** — `BinderPage` ma pole `firestoreId: String?` (klucz dokumentu w Firestore) bezpośrednio eksponowane jako parametr funkcji Composable (`CapDetailView.kt:48`). Wymiana Room na inny ORM lub przejście na DataStore wymagałaby zmiany sygnatury UI composable.

3. **Rozjazd intencja–kod** — `prd.md:141` mówi: *"Dane struktury klaserów przechowywane wyłącznie na urządzeniu"* — sugerując, że persystencja jest szczegółem data-layer. Tymczasem `CollectionVerificationScreen.kt:103` przyjmuje `cap: CapCache` (Room `@Entity`) jako argument Composable — widok renderujący zna kolumny tabeli SQLite (`catalog_status`, `last_verified_at`).

4. **Semantyczna niepoprawność** — `BindersUiState.binders: List<Binder>` jest publicznym stanem UI, ale `Binder` to klasa oznaczona `@Entity(tableName = "binder")` — schemat bazy danych w kontrakcie stanu interfejsu użytkownika.

---

## KROK 3 — Diagnoza

### Mapa przecieków encji Room do UI

```
┌─────────────────────────────────────────────────────────────────────┐
│ data/datasource/local/entity/                                        │
│   Binder         @Entity("binder")           id, name, firestoreId  │
│   BinderPage     @Entity("binder_page")       id, binderId,          │
│                  @ForeignKey(Binder::class)   pageNumber, firestoreId│
│   CapPosition    @Entity("cap_position")      id, binderPageId,      │
│                  @ForeignKey(BinderPage::class) position, capId      │
│   CapCache       @Entity("cap_cache")         capId, name, country,  │
│                  + 7 @ColumnInfo fields        imageUrl,             │
│                                               catalogStatus, ...    │
├─────────────────────────────────────────────────────────────────────┤
│ PRZECIEK: te encje wychodzą z warstwy data/ do ui/                  │
├─────────────────────────────────────────────────────────────────────┤
│ ui/binders/BindersViewModel.kt:31-33                                 │
│   import Binder, BinderPage, CapPosition                            │
│                                                                      │
│ data class BindersUiState(                      ← publiczny stan UI  │
│     val binders: List<Binder> = emptyList(),    ← Room @Entity       │
│     val binderPages: Map<Long, List<BinderPage>>,← Room @Entity      │
│     val capPositions: Map<Long, List<CapPosition>>,← Room @Entity    │
│     ...                                                              │
│ )                                                                    │
│                                                                      │
│ ui/binders/BindersScreen.kt:51-53                                    │
│   import Binder, BinderPage, CapPosition        ← Screen zna encje  │
│                                                                      │
│ ui/statistics/verification/                                          │
│   CollectionVerificationViewModel.kt:27                              │
│     val flaggedCaps: StateFlow<List<CapCache>>  ← Room @Entity w Flow│
│                                                                      │
│   CollectionVerificationScreen.kt:103                                │
│     fun CapCard(cap: CapCache, ...)             ← COMPOSABLE        │
│               ↑ parametr Composable = encja Room!                    │
│                                                                      │
│ ui/catalog/caps/detail/CapDetailView.kt:47-48                        │
│   fun CapDetailView(                                                  │
│       binders: List<Binder> = emptyList(),      ← Room @Entity       │
│       binderPages: List<BinderPage> = emptyList(),← Room @Entity     │
│   )                                                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Groźny szczegół: `firestoreId` widoczny w UI

```kotlin
// data/datasource/local/entity/BinderPage.kt:24-29
@Entity(tableName = "binder_page", foreignKeys = [...], indices = [...])
data class BinderPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "binder_id")    val binderId: Long,
    @ColumnInfo(name = "page_number")  val pageNumber: Int,
    @ColumnInfo(name = "firestore_id") val firestoreId: String? = null  // ← sync klucz Firestore
)
```

`firestoreId` jest kluczem dokumentu w Firebase Firestore — szczegółem implementacyjnym synchronizacji chmurowej. Jest on bezpośrednio dostępny jako pole `binderPages` w `BindersUiState` i `CapDetailViewModel.binderPages: List<BinderPage>`. Każdy ViewModel (i poprzez niego composable) może odczytać klucze Firestore, choć nigdy nie powinien ich znać.

### PRD: deklaracja wymienialności (linia prd.md:141)

> *"Dane struktury klaserów przechowywane wyłącznie na urządzeniu — nie synchronizowane z crowncaps.info."*

Intencja: persystencja struktury klaserów to detal warstwy danych. Kod nie dotrzymuje tej intencji: schema Room (encje z kluczami Firestore, UNIQUE indexami, FK constraints) jest bezpośrednio widoczna w warstwie UI.

---

## KROK 4 — Projekt ACL

### Domenowe value objects — jedyne miejsce wiedzy o "kształcie" klaserów

```kotlin
// model/binder/BinderView.kt
// Domenowy widok klasera — bez adnotacji Room, bez firestoreId
data class BinderView(
    val id: Long,
    val name: String
)
```

```kotlin
// model/binder/BinderPageView.kt
// Domenowy widok strony klasera
data class BinderPageView(
    val id: Long,
    val binderId: Long,
    val pageNumber: Int
)
```

```kotlin
// model/binder/CapSlot.kt
// Domenowy widok pozycji kapsla — bez FK constraints, bez firestoreId
data class CapSlot(
    val id: Long,
    val binderPageId: Long,
    val position: Int,
    val capId: Long
)
```

```kotlin
// model/binder/CatalogStatus.kt
// Enum zastępujący magiczne stringi "ok"/"missing"/"swapped"/"updated"
enum class CatalogStatus { OK, MISSING, SWAPPED, UPDATED, UNKNOWN;

    companion object {
        fun from(raw: String): CatalogStatus = when (raw) {
            "ok"      -> OK
            "missing" -> MISSING
            "swapped" -> SWAPPED
            "updated" -> UPDATED
            else      -> UNKNOWN
        }
    }
}
```

```kotlin
// model/binder/FlaggedCapView.kt
// Domenowy widok kapsla z rozjazdem — bez adnotacji Room, bez nullable Long "lastVerifiedAt"
data class FlaggedCapView(
    val capId: Long,
    val name: String,
    val country: String,
    val imageUrl: String,
    val catalogStatus: CatalogStatus,
    val createdAt: String?,
    val createdById: Int?,
    val updatedAt: String?
)
```

### Wąski port — interfejsy domenowe zwracające domain models

```kotlin
// Zmiana sygnatury w data/BinderRepository.kt — PRZED (zwraca Room entity)
fun getAll(): Flow<List<Binder>>   // ← przeciek

// PO (zwraca domain model)
fun getAll(): Flow<List<BinderView>>
```

```kotlin
// Zmiana sygnatury w data/BinderPageRepository.kt
fun getByBinder(binderId: Long): Flow<List<BinderPage>>   // ← przeciek
fun getByBinder(binderId: Long): Flow<List<BinderPageView>>
```

```kotlin
// Zmiana sygnatury w data/CapPositionRepository.kt
fun getByPage(pageId: Long): Flow<List<CapPosition>>   // ← przeciek
fun getByPage(pageId: Long): Flow<List<CapSlot>>
```

```kotlin
// Zmiana sygnatury w data/CapCacheRepository.kt
fun flaggedCapsFlow(): Flow<List<CapCache>>           // ← przeciek
fun flaggedCapsFlow(): Flow<List<FlaggedCapView>>
```

### Adapter — jedyne miejsce mapowania Room entity ↔ domain model

```kotlin
// Mapowania w implementacjach repozytoriów — prywatne, nie eksponowane

// W BinderRepository:
private fun Binder.toDomain() = BinderView(id = id, name = name)

// W BinderPageRepository:
private fun BinderPage.toDomain() = BinderPageView(
    id = id, binderId = binderId, pageNumber = pageNumber
    // firestoreId: nie eksponowane — persystuje tylko w data-layer
)

// W CapPositionRepository:
private fun CapPosition.toDomain() = CapSlot(
    id = id, binderPageId = binderPageId, position = position, capId = capId
)

// W CapCacheRepository:
private fun CapCache.toDomain() = FlaggedCapView(
    capId = capId,
    name = name,
    country = country,
    imageUrl = imageUrl,
    catalogStatus = CatalogStatus.from(catalogStatus),
    createdAt = createdAt,
    createdById = createdById,
    updatedAt = updatedAt
    // lastVerifiedAt: nie eksponowane — szczegół harmonogramu weryfikacji
)
```

---

## KROK 5 — Dowód izolacji + before/after

### Kryterium sukcesu

```bash
# PRZED refaktorem:
grep -r "datasource.local.entity" app/src/main/java/pl/sroki/cci/android/ui
# → 13 trafień w 6 plikach UI

# PO refaktorze:
grep -r "datasource.local.entity" app/src/main/java/pl/sroki/cci/android/ui
# → 0 trafień
```

Wymiana Room na inną bibliotekę persystencji dotknęłaby **wyłącznie**:
- `data/datasource/local/entity/*.kt` — definicje encji
- `data/datasource/local/dao/*.kt` — DAO queries
- `data/datasource/local/CciDatabase.kt` — definicja bazy
- `data/*Repository.kt` (metody `toDomain()`) — mapowanie
- `di/DatabaseModule.kt` — konfiguracja DI

### Before/after dla każdego miejsca przecieku

#### Miejsce 1: BindersUiState

```kotlin
// BEFORE — ui/binders/BindersViewModel.kt:39-52
// Publiczny stan UI z Room entities
data class BindersUiState(
    val binders: List<Binder> = emptyList(),             // ← @Entity
    val binderPages: Map<Long, List<BinderPage>> = ...,  // ← @Entity + @ForeignKey
    val capPositions: Map<Long, List<CapPosition>> = ..., // ← @Entity + @ForeignKey
    ...
)

// AFTER
data class BindersUiState(
    val binders: List<BinderView> = emptyList(),              // ← domain model, bez Room
    val binderPages: Map<Long, List<BinderPageView>> = ...,   // ← bez firestoreId
    val capPositions: Map<Long, List<CapSlot>> = ...,          // ← bez FK constraints
    ...
)
// Usunięte importy: entity.Binder, entity.BinderPage, entity.CapPosition
```

#### Miejsce 2: CollectionVerificationViewModel

```kotlin
// BEFORE — CollectionVerificationViewModel.kt:27
// StateFlow eksponuje Room @Entity
val flaggedCaps: StateFlow<List<CapCache>> = capCacheRepository.flaggedCapsFlow()
    .stateIn(...)

// AFTER
val flaggedCaps: StateFlow<List<FlaggedCapView>> = capCacheRepository.flaggedCapsFlow()
    .stateIn(...)
// Usunięty import: entity.CapCache
// catalogStatus to teraz CatalogStatus enum, nie String
```

#### Miejsce 3: CollectionVerificationScreen — Composable z parametrem Room entity

```kotlin
// BEFORE — CollectionVerificationScreen.kt:103
@Composable
fun CapCard(cap: CapCache, onKeep: () -> Unit, onUnlink: () -> Unit) {
    Text(text = cap.name)
    Text(text = cap.country)
    // cap.catalogStatus to "missing"/"swapped"/... — magiczny string z DB schema
    val isSwapped = cap.catalogStatus == "swapped"
    ...
}

// AFTER
@Composable
fun CapCard(cap: FlaggedCapView, onKeep: () -> Unit, onUnlink: () -> Unit) {
    Text(text = cap.name)
    Text(text = cap.country)
    // cap.catalogStatus to CatalogStatus enum — domenowy typ, brak magicznych stringów
    val isSwapped = cap.catalogStatus == CatalogStatus.SWAPPED
    ...
}
// Composable nie zna struktury tabeli SQLite; nie importuje entity.CapCache
```

#### Miejsce 4: CapDetailView — Composable z parametrami Room entities

```kotlin
// BEFORE — CapDetailView.kt:47-48
@Composable
fun CapDetailView(
    ...
    binders: List<Binder> = emptyList(),          // ← Room @Entity
    binderPages: List<BinderPage> = emptyList(),  // ← Room @Entity + firestoreId
    ...
)

// AFTER
@Composable
fun CapDetailView(
    ...
    binders: List<BinderView> = emptyList(),          // ← domain model
    binderPages: List<BinderPageView> = emptyList(),  // ← domain model, bez firestoreId
    ...
)
// Composable nie importuje żadnego typu z data.datasource.local.entity
```

#### Miejsce 5: CapDetailViewModel — pola Room entities

```kotlin
// BEFORE — CapDetailViewModel.kt:60-63
var binders: List<Binder> by mutableStateOf(emptyList())       // ← Room @Entity
    private set
var binderPages: List<BinderPage> by mutableStateOf(emptyList()) // ← Room @Entity
    private set

// AFTER
var binders: List<BinderView> by mutableStateOf(emptyList())       // ← domain model
    private set
var binderPages: List<BinderPageView> by mutableStateOf(emptyList()) // ← domain model
    private set
// Usunięte importy: entity.Binder, entity.BinderPage
// Wywołania: binderRepository.getAll() → List<BinderView> bez mapowania w ViewModel
```

### Otwarte pytania zależne od kontraktu Room — rozstrzygnięcie w ACL

**Pytanie 1:** `BinderPage.firestoreId` — jest potrzebne w repozytoriach do operacji Firestore sync. Gdzie zakodować decyzję?

**Rozstrzygnięcie:** `firestoreId` jest szczegółem implementacji warstwy sync. Zostaje w encji Room `BinderPage` (jako pole wewnętrzne) i jest dostępne wyłącznie wewnątrz `BinderPageRepository` i serwisów Firestore. `BinderPageView` (domain model) **nie ma** pola `firestoreId`. Firestore sync odczytuje `firestoreId` bezpośrednio z encji Room — nie przez API domenowe.

**Pytanie 2:** `CapCache.catalogStatus` to `String` w DB. Jak zachować kompatybilność przy zmianie?

**Rozstrzygnięcie:** Mapowanie `String → CatalogStatus` (enum) żyje wyłącznie w `CapCacheRepository.CapCache.toDomain()`. Dodanie nowej wartości `catalogStatus` w bazie wymaga tylko dodania wpisu do `CatalogStatus.from()` — zero zmian w UI.

---

## KROK 6 — Weryfikacja i plan faz

### Kryterium sukcesu (maszynowe)

```bash
# Cel: zero trafień poza data/
grep -r "datasource.local.entity" \
  app/src/main/java/pl/sroki/cci/android/ui \
  app/src/main/java/pl/sroki/cci/android/model

# Oczekiwany output po refaktorze: (empty)
```

### Pliki: przed i po

| Plik | Przed (zna encję Room) | Po refaktorze |
|------|------------------------|---------------|
| `ui/binders/BindersScreen.kt:51-53` | `Binder`, `BinderPage`, `CapPosition` | **Brak importów Room** |
| `ui/binders/BindersViewModel.kt:30-33` | `CapCache`, `Binder`, `BinderPage`, `CapPosition` | **Brak importów Room** |
| `ui/catalog/caps/detail/CapDetailView.kt:34-35` | `Binder`, `BinderPage` | **Brak importów Room** |
| `ui/catalog/caps/detail/CapDetailViewModel.kt:23-24` | `Binder`, `BinderPage` | **Brak importów Room** |
| `ui/statistics/verification/CollectionVerificationViewModel.kt:15` | `CapCache` | **Brak importów Room** |
| `ui/statistics/verification/CollectionVerificationScreen.kt:38` | `CapCache` | **Brak importów Room** |
| `data/model/OwnedCapRow.kt:3` | `@ColumnInfo` | Zostaje — nie przecieka do UI (data-internal) |
| `data/model/CountryStatRow.kt:3` | `@ColumnInfo` | Zostaje — nie przecieka do UI (data-internal) |

### Plan faz (zgodny z konwencją projektu)

#### Faza 1 — Domain models (test-first, bez zmian istniejącego kodu)

| Krok | Plik | Opis |
|------|------|------|
| 1a | `model/binder/BinderView.kt` | Nowy domain model — `data class BinderView(id, name)` |
| 1b | `model/binder/BinderPageView.kt` | Nowy domain model — `data class BinderPageView(id, binderId, pageNumber)` |
| 1c | `model/binder/CapSlot.kt` | Nowy domain model — `data class CapSlot(id, binderPageId, position, capId)` |
| 1d | `model/binder/CatalogStatus.kt` | Enum z `from(String)` — zastępuje magiczne stringi DB |
| 1e | `model/binder/FlaggedCapView.kt` | Nowy domain model — `data class FlaggedCapView(...)` |

**Warunek zaliczenia fazy 1:** `./gradlew build` zielony; nowe pliki kompilują się bez błędów.

#### Faza 2 — Mapowania w repozytoriach (adapter)

| Krok | Plik | Opis |
|------|------|------|
| 2a | `data/BinderRepository.kt` | Dodaj prywatne `Binder.toDomain()`, zmień `getAll()` → `Flow<List<BinderView>>` |
| 2b | `data/BinderPageRepository.kt` | Dodaj `BinderPage.toDomain()`, zmień `getByBinder()` → `Flow<List<BinderPageView>>` |
| 2c | `data/CapPositionRepository.kt` | Dodaj `CapPosition.toDomain()`, zmień `getByPage()` → `Flow<List<CapSlot>>` |
| 2d | `data/CapCacheRepository.kt` | Dodaj `CapCache.toDomain()`, zmień `flaggedCapsFlow()` → `Flow<List<FlaggedCapView>>` |

**Warunek zaliczenia fazy 2:** `./gradlew build` zielony; `grep -r "datasource.local.entity" data/` nadal prawidłowy (wyłącznie entity/, dao/, CciDatabase, repozytoria — bez UI).

#### Faza 3 — Migracja UI (ViewModele i Composable)

| Krok | Plik | Opis |
|------|------|------|
| 3a | `ui/binders/BindersViewModel.kt` | Usuń importy Room entities; zaktualizuj `BindersUiState` |
| 3b | `ui/binders/BindersScreen.kt` | Usuń importy Room entities; typy parametrów zaktualizowane |
| 3c | `ui/catalog/caps/detail/CapDetailViewModel.kt` | Usuń importy `Binder`, `BinderPage` |
| 3d | `ui/catalog/caps/detail/CapDetailView.kt` | Zmień parametry `binders`, `binderPages` na domain models |
| 3e | `ui/statistics/verification/CollectionVerificationViewModel.kt` | Usuń import `CapCache`; `flaggedCaps: StateFlow<List<FlaggedCapView>>` |
| 3f | `ui/statistics/verification/CollectionVerificationScreen.kt` | Usuń import `CapCache`; parametr Composable → `FlaggedCapView` |

**Warunek zaliczenia fazy 3:** `./gradlew build` zielony; manualna weryfikacja widoku klaserów i ekranu weryfikacji.

#### Faza 4 — Weryfikacja (grep + test)

| Krok | Opis |
|------|------|
| 4a | `grep -r "datasource.local.entity" app/src/main/java/pl/sroki/cci/android/ui` → 0 wyników |
| 4b | `grep -r "datasource.local.entity" app/src/main/java/pl/sroki/cci/android/model` → 0 wyników |
| 4c | `./gradlew testDebugUnitTest` — wszystkie testy zielone |

---

## Podsumowanie

Najpoważniejszym przeciekiem zależności w CCI Android jest bezpośrednie eksponowanie encji Room (`Binder`, `BinderPage`, `CapPosition`, `CapCache`) do warstwy UI — 6 plików w `ui/` importuje typy z `data/datasource/local/entity/`, a `CapDetailView` i `CollectionVerificationScreen` przyjmują Room entities jako parametry Composable. Oznacza to, że schemat bazy SQLite (włącznie z kluczami synchronizacji Firestore i wewnętrznymi stringami statusów) jest bezpośrednio widoczny w warstwie renderowania. PRD deklaruje, że dane klaserów mają być szczegółem implementacyjnym (`prd.md:141`), ale kod nie dotrzymuje tej deklaracji. Plan czterofazowy wprowadza pięć nowych domain models (`BinderView`, `BinderPageView`, `CapSlot`, `CatalogStatus`, `FlaggedCapView`) bez adnotacji Room, lokalizuje mapowanie Room→domain wyłącznie w implementacjach repozytoriów (adapter), i migruje ViewModele oraz Composable do używania wyłącznie tych typów domenowych. Kryterium sukcesu: `grep -r "datasource.local.entity" ui/` → 0 wyników; wymiana Room na inny ORM dotyka wyłącznie `data/datasource/local/` i metod mapujących w repozytoriach.

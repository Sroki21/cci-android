---
title: "CCI Android — Agregat CollectionEntry: plan refaktoru niezmiennika trójstanowego"
created: 2026-06-16
type: refactor-plan
---

# CCI Android — Plan refaktoru: niezmiennik trójstanowego stanu posiadania

---

## KROK 0 — Kontekst projektu

### Dokumenty bazowe

| Dokument | Rola |
|----------|------|
| `context/foundation/prd.md` | Główne wymagania, reguły biznesowe, Guardrails |
| `context/domain/01-domain-distillation.md` | Wcześniejsza destylacja domeny, klasyfikacja subdomen, rankingi |
| `context/foundation/roadmap.md` | Historia zmian, kontekst Firestore Sync |

### Stack i warstwy logiki biznesowej

```
Android (Kotlin 2.1.20) / Jetpack Compose / Hilt DI / Retrofit / Room / Firebase Firestore

Warstwa danych:  data/*Repository.kt           ← gdzie powinna żyć logika domeny
Warstwa UI:      ui/.../CapDetailViewModel.kt  ← gdzie faktycznie żyje (wyciek)
Persystencja:    Room + SharedPreferences + Firestore (dual-write)
API zewnętrzne:  crowncaps.info (Laravel Sanctum)
```

Logika biznesowa *powinna* żyć w `data/*Repository.kt`. Faktycznie duża jej część **wycieka** do
`CapDetailViewModel.kt` (500 linii): inferowanie trójstanu, synchronizacja lokalnego store'u,
algorytm sugestii klasera.

---

## KROK 1 — Niezmienniki biznesowe

| ID | Reguła | Źródło | Kod (plik:linia) |
|----|--------|--------|------------------|
| **N-1** | Stan posiadania kapsla jest zawsze jednym z trzech: `MISSING` (nie posiadam), `PURCHASED` (posiadam, nieskatalogowany), `IN_COLLECTION` (posiadam + przypisany do pozycji). Żaden czwarty stan nie istnieje. | `prd.md:132` "Wyjście reguły to jeden z trzech stanów" | `CapDetailViewModel.kt:32` — enum `CapStatus` (tylko lokalnie w tym ViewModel) |
| **N-2** | Kapsel nie może być jednocześnie w stanie `PURCHASED` i `IN_COLLECTION` — są wzajemnie wykluczające. Po katalogowaniu (assign) kapsel natychmiast znika z listy oczekujących. | `prd.md:49` "kapsel znika z listy oczekujących" | `PurchasedViewModel.kt:48-49` — kompensacja UI: `purchasedIds - assignedIds`; brak transakcji Room łączącej obie operacje |
| **N-3** | Przejście `MISSING → PURCHASED` (oznaczenie "kupuję") wymaga sukcesu wywołania API crowncaps.info. Jeśli API zawiedzie, stan lokalny **nie** zmienia się. | `prd.md:71` "Jeżeli rejestracja nie powiedzie się, kapsel NIE jest oznaczony" | `CapsRepository.kt:90-96` — sekwencja: API call → `purchasedCapsLocalStore.add()` (poprawna kolejność) |
| **N-4** | Slot (strona, pozycja) jest zawsze unikalny — żadne dwa kapsle nie zajmują tej samej pozycji jednocześnie. | `prd.md:55` "Slot jest zawsze unikalny — aplikacja blokuje" | `CapPosition.kt:19-21` UNIQUE INDEX; `CapPositionDao.kt:22` `OnConflictStrategy.IGNORE` — cichy brak feedbacku |
| **N-5** | Klaser ma od 1 do 15 stron (granica górna i dolna). Poniżej 1 — nie istnieje; powyżej 15 — czytelny błąd. | `prd.md:105` "FR-011: limit 1–15 na klaser" | `BinderPageRepository.kt:32` `check(count < 15)` (górna OK); brak `check(count > 1)` przy `deletePage` (dolna brak) |
| **N-6** | Pozycja na stronie mieści się w zakresie 1–35. | `prd.md:105` "każda strona ma zawsze 35 pozycji" | `CapPositionRepository.kt:32` `require(position in 1..35)` — egzekwowany |
| **N-7** | Klaser zawierający kapsle nie może być usunięty. | `prd.md:99` "usuwanie tylko pustego klasera" | `BinderRepository.kt:33-34` `check(occupied == 0)` — egzekwowany |

---

## KROK 2 — Klasyfikacja i wybór niezmiennika #1

### Macierz klasyfikacji

| Niezmiennik | (a) Rdzeniowy dla produktu | (b) Rozsmarowany po warstwach | (c) Egzekwowany | Priorytet |
|-------------|---------------------------|-------------------------------|-----------------|-----------|
| **N-1** (trójstanowy model) | ⭐⭐⭐⭐⭐ — rdzeń North Star: "czy mam?" + "gdzie jest?" | 4 warstwy: API → SharedPrefs → Room CapPosition → ViewModel | ❌ enum tylko w `CapDetailViewModel.kt:32`; brak shared domain model | **#1** |
| **N-2** (pending XOR assigned) | ⭐⭐⭐⭐⭐ — to samo North Star, drugi aspekt | ViewModel + SharedPrefs + Room — bez transakcji łączącej | ❌ kompensacja w `PurchasedViewModel.kt:48-49` (UI strażnik) | **#1** (powiązany z N-1) |
| **N-3** (API-first przy zakupie) | ⭐⭐⭐⭐ — gwarancja spójności API↔lokalny | `CapsRepository.kt:90-96` | ✅ poprawna kolejność operacji | #3 |
| **N-4** (slot unikalny) | ⭐⭐⭐⭐ — guardrail PRD | DB UNIQUE constraint + IGNORE | ⚠️ brak feedbacku UI, dane bezpieczne | #4 |
| **N-5** (1–15 stron) | ⭐⭐⭐ | `BinderPageRepository.kt` | ✅ górna / ❌ dolna (brak sprawdzenia min) | #5 |
| **N-6** (1–35 pozycji) | ⭐⭐⭐ | `CapPositionRepository.kt` | ✅ pełne | #6 |
| **N-7** (klaser niepusty) | ⭐⭐⭐ | `BinderRepository.kt` | ✅ pełne | #7 |

### Wybrany niezmiennik

> **N-1 + N-2 (traktowane jako jeden niezmiennik):**
> "Stan posiadania kapsla jest zawsze dokładnie jedną z trzech wartości: `MISSING`, `PURCHASED`,
> `IN_COLLECTION`. Każde przejście między stanami jest atomowe — kapsel nigdy nie może być
> jednocześnie `PURCHASED` i `IN_COLLECTION`."

**Uzasadnienie wyboru:**

1. **Najbardziej rdzeniowy** — odpowiada bezpośrednio na oba cele North Star produktu
   (`prd.md:48-49`): "czy mam?" i "gdzie jest?". Bez poprawnego trójstanu odpowiedź na
   oba pytania jest potencjalnie błędna.

2. **Najsłabiej egzekwowany** — jedyna formalna definicja (`enum class CapStatus`) żyje
   wyłącznie w `CapDetailViewModel.kt:32`. Trzy inne ViewModele/komponenty re-implementują
   własne heurystyki stanu. Między przejściem `PURCHASED → IN_COLLECTION` nie ma transakcji
   Room łączącej usunięcie z `pending_cap` i wstawienie do `cap_position`.

3. **Aktywnie naruszalny** — leakage w `CapDetailViewModel.saveAssignment():162-168`:
   brak `purchasedCapsLocalStore.remove(capId)` po przypisaniu do pozycji. Kapsel zostaje
   jednocześnie w `PurchasedCapsLocalStore` i `cap_position`. Jedyną ochroną przed
   wyświetleniem błędnych danych jest kompensacja UI w `PurchasedViewModel.kt:48-49`.

---

## KROK 3 — Diagnoza wybranego niezmiennika

### Mapa miejsc, gdzie reguła żyje

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Warstwa API / zdalny stan                                                   │
│   crowncaps.info → Cap.isInCollection (Boolean, prd.md:34)                 │
│   CapsRepository.kt:90-96  addToCollection() — API → purchasedStore.add()  │
│   CapsRepository.kt:98-104 removeFromCollection() — API → purchasedStore.remove() │
│                                                                             │
│ Warstwa lokalnego przechowywania — PURCHASED state                          │
│   PurchasedCapsLocalStore.kt:9  SharedPreferences "purchased_caps"         │
│   PendingCapRepository.kt:11    Room entity pending_cap ← MARTWY KOD       │
│                                 (zero konsumentów w ViewModelach)           │
│                                                                             │
│ Warstwa lokalnego przechowywania — IN_COLLECTION state                      │
│   CapPositionRepository.kt:31-69  cap_position (Room)                      │
│                                                                             │
│ Warstwa domeny / logiki — BRAK; stan inferowany w:                          │
│   CapDetailViewModel.kt:232-241  getCap() — inferuje CapStatus z API+Room  │
│   CapDetailViewModel.kt:32       enum CapStatus { IN_COLLECTION, PURCHASED, MISSING } │
│   PurchasedViewModel.kt:48-49    purchasedIds - assignedIds (kompensacja)  │
│   CapGridView.kt:28-32           inline warunkowe (re-implementacja stanu) │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Szczegółowe cytaty

**1. Jedyna formalna definicja trójstanu:**
```kotlin
// CapDetailViewModel.kt:32
enum class CapStatus { IN_COLLECTION, PURCHASED, MISSING }
```
*Problem:* Prywatny enum w klasie UI — niewidoczny poza tym plikiem, nie trafia do testów
jednostkowych warstwy danych.

**2. Inferowanie stanu w CapDetailViewModel.getCap():**
```kotlin
// CapDetailViewModel.kt:229-241
val status = when {
    binderInfo != null -> CapStatus.IN_COLLECTION
    cap.isInCollection -> CapStatus.PURCHASED
    else -> CapStatus.MISSING
}
when (status) {
    CapStatus.PURCHASED -> purchasedCapsLocalStore.add(id.toLong())
    CapStatus.MISSING -> purchasedCapsLocalStore.remove(id.toLong())
    else -> Unit
}
```
*Problem:* Synchronizacja `purchasedCapsLocalStore` odbywa się **tutaj, w ViewModel**, a nie
w domenie. Każde wywołanie `getCap()` może nadpisać stan lokalny na podstawie odpowiedzi API.

**3. Leakage — brak usunięcia z purchased store przy przypisaniu:**
```kotlin
// CapDetailViewModel.kt:148-184  saveAssignment()
if (current.status == CapStatus.IN_COLLECTION) {
    capPositionRepository.reassign(capId, pageId, position, snapshot)     // OK
} else {
    if (!current.cap.isInCollection) {
        repository.addToCollection(current.cap.id)                        // API + add do store
    }
    capPositionRepository.assign(pageId, position, capId, snapshot)       // Room insert
    // ❌ BRAK: purchasedCapsLocalStore.remove(capId)
    // Kapsel pozostaje w SharedPreferences jako "purchased"
    // i jednocześnie ma wiersz w cap_position jako "in_collection"
}
```

**4. UI strażnik w PurchasedViewModel — jedyne miejsce egzekwowania N-2:**
```kotlin
// PurchasedViewModel.kt:43-54
val assignedIds = capPositionRepository.getAllCapIds().toSet()
val purchasedIds = purchasedCapsLocalStore.getIds() - assignedIds   // kompensacja
val caps = purchasedIds.mapNotNull { id -> ... }
```
*Problem:* Dwa oddzielne odczyty (`getAllCapIds()` + `getIds()`) bez transakcji — mogą pokazać
niespójny snapshot między wywołaniami. Logika domenowa żyje w ViewModel.

**5. Re-implementacja trójstanu w CapGridView:**
```kotlin
// CapGridView.kt:27-32
val assignedCapIds = LocalAssignedCapIds.current
val border = when {
    !cap.isInCollection -> null                           // MISSING
    cap.id in assignedCapIds -> BorderStroke(6.dp, Color(0xFF00FF00))  // IN_COLLECTION
    else -> BorderStroke(6.dp, Color(0xFF2196F3))         // PURCHASED
}
```
*Problem:* Trzecia, niezależna re-implementacja trójstanu — tym razem jako inline wyrażenie
warunkowe w composable. Żaden test nie weryfikuje spójności tej logiki z logiką w
`CapDetailViewModel`.

**6. PendingCapRepository — martwy kod:**
```kotlin
// PendingCapRepository.kt:11-23
@Singleton
class PendingCapRepository @Inject constructor(private val dao: PendingCapDao) {
    fun getAll(): Flow<List<Long>> = dao.getAll().map { list -> list.map { it.capId } }
    suspend fun add(capId: Long) { dao.insert(PendingCap(capId)) }
    suspend fun remove(capId: Long) { dao.deleteById(capId) }
}
```
*Problem:* Interfejs poprawnie zbudowany; Room zapewnia reaktywny `Flow`, transakcje i migracje
— których `PurchasedCapsLocalStore` (SharedPreferences) nie ma. Żaden ViewModel nie wstrzykuje
ani nie wywołuje `PendingCapRepository`.

### Podsumowanie luk egzekwowania

| Luka | Lokalizacja | Skutek |
|------|-------------|--------|
| Brak transakcji `pending_cap` ↔ `cap_position` | `CapDetailViewModel.saveAssignment():148-184` | Kapsel w stanie PURCHASED i IN_COLLECTION jednocześnie w store |
| Brak domain model poza CapDetailViewModel | Cały projekt | Każdy ViewModel/composable re-implementuje własną heurystykę stanu |
| Martwy PendingCapRepository | `PendingCapRepository.kt` | Poprawne narzędzie (Room, Flow) nie jest używane; trwa SharedPrefs |
| Kompensacja stanu w UI | `PurchasedViewModel.kt:48-49` | Logika domenowa w warstwie prezentacji; wrażliwa na race conditions |

---

## KROK 4 — Projekt agregatu-strażnika

### Agregat root: `CollectionEntry`

Jedyne miejsce egzekwowania reguły trójstanowej. Metody domenowe z preconditions; nielegalna
operacja rzuca nazwany błąd domenowy — nie cicho aktualizuje stanu.

```kotlin
// model/CollectionEntry.kt
data class CollectionEntry private constructor(
    val capId: Long,
    val state: OwnershipState
) {
    enum class OwnershipState { MISSING, PURCHASED, IN_COLLECTION }

    companion object {
        fun resolve(capId: Long, isInCollection: Boolean, hasPosition: Boolean): CollectionEntry {
            val state = when {
                hasPosition      -> OwnershipState.IN_COLLECTION
                isInCollection   -> OwnershipState.PURCHASED
                else             -> OwnershipState.MISSING
            }
            return CollectionEntry(capId, state)
        }
    }

    fun markBought(): CollectionEntry {
        check(state == OwnershipState.MISSING) {
            throw CollectionException.AlreadyOwned(capId)
        }
        return copy(state = OwnershipState.PURCHASED)
    }

    fun catalog(): CollectionEntry {
        check(state == OwnershipState.PURCHASED) {
            throw CollectionException.NotPurchased(capId, state)
        }
        return copy(state = OwnershipState.IN_COLLECTION)
    }

    fun uncatalog(): CollectionEntry {
        check(state == OwnershipState.IN_COLLECTION) {
            throw CollectionException.NotAssigned(capId)
        }
        return copy(state = OwnershipState.PURCHASED)
    }

    fun removeFromCollection(): CollectionEntry {
        check(state != OwnershipState.MISSING) {
            throw CollectionException.NotOwned(capId)
        }
        return copy(state = OwnershipState.MISSING)
    }
}
```

### Błędy domenowe: `CollectionException`

```kotlin
// model/CollectionException.kt
sealed class CollectionException(msg: String) : Exception(msg) {
    class AlreadyOwned(capId: Long) :
        CollectionException("Cap $capId jest już posiadany")
    class NotPurchased(capId: Long, current: CollectionEntry.OwnershipState) :
        CollectionException("Cap $capId musi być PURCHASED przed katalogowaniem; stan: $current")
    class NotAssigned(capId: Long) :
        CollectionException("Cap $capId nie jest w klaserze (IN_COLLECTION)")
    class NotOwned(capId: Long) :
        CollectionException("Cap $capId nie jest posiadany — nie można usunąć z kolekcji")
    class ApiFailure(capId: Long, cause: Throwable) :
        CollectionException("Błąd API dla cap $capId: ${cause.message}")
}
```

### Repozytorium agregatu: `CollectionRepository`

```kotlin
// data/CollectionRepository.kt  (interface)
interface CollectionRepository {
    /** Inferuje bieżący stan z API + Room. */
    suspend fun get(capId: Long): CollectionEntry

    /** MISSING → PURCHASED. Sekwencja: API call → Room pending_cap insert.
     *  Rzuca CollectionException.ApiFailure jeśli API zawiedzie — stan lokalny nienaruszone. */
    suspend fun markBought(capId: Long): CollectionEntry

    /** PURCHASED → IN_COLLECTION. Atomowa transakcja Room:
     *  DELETE pending_cap WHERE cap_id = :capId
     *  INSERT cap_position(binderPageId, position, capId)
     *  Rzuca CollectionException.NotPurchased jeśli cap nie jest w stanie PURCHASED. */
    suspend fun catalog(
        capId: Long,
        binderPageId: Long,
        position: Int,
        snapshot: CapSnapshot
    ): CollectionEntry

    /** IN_COLLECTION → IN_COLLECTION (inna pozycja). Atomowa transakcja Room. */
    suspend fun recatalog(
        capId: Long,
        newBinderPageId: Long,
        newPosition: Int,
        snapshot: CapSnapshot
    ): CollectionEntry

    /** IN_COLLECTION → PURCHASED. Atomowa transakcja Room:
     *  DELETE cap_position WHERE cap_id = :capId
     *  INSERT pending_cap(cap_id)  */
    suspend fun uncatalog(capId: Long): CollectionEntry

    /** PURCHASED/IN_COLLECTION → MISSING. Sekwencja: API call → Room cleanup.
     *  Rzuca CollectionException.ApiFailure jeśli API zawiedzie. */
    suspend fun removeFromCollection(capId: Long): CollectionEntry

    /** Reaktywny strumień ID kapsli w stanie PURCHASED (z Room pending_cap). */
    fun getPendingFlow(): Flow<List<Long>>
}
```

### Implementacja atomowej operacji `catalog()` (pseudokod)

```kotlin
// CollectionRepositoryImpl.catalog()
override suspend fun catalog(
    capId: Long, binderPageId: Long, position: Int, snapshot: CapSnapshot
): CollectionEntry {
    val entry = get(capId)
    val updated = entry.catalog()  // ← fail-fast: rzuca CollectionException.NotPurchased

    // Weryfikacja API — jeśli cap nie jest jeszcze na crowncaps.info, oznacz najpierw.
    if (entry.state == CollectionEntry.OwnershipState.PURCHASED
        && !isInApiCollection(capId)) {
        runCatching { capsApiService.addToCollection(capId) }
            .getOrElse { e -> throw CollectionException.ApiFailure(capId, e) }
    }

    db.withTransaction {
        pendingCapDao.deleteById(capId)     // ← usuń z pending_cap (PURCHASED)
        capPositionDao.insert(              // ← wstaw do cap_position (IN_COLLECTION)
            CapPosition(binderPageId = binderPageId, position = position, capId = capId)
        )
    }
    // Firestore dual-write (poza transakcją Room — scheduled job)
    authManager.uid.value?.let { uid ->
        binderPageDao.getById(binderPageId)?.firestoreId?.let {
            capPositionFirestoreService.scheduleCreate(uid, it, position, capId, snapshot)
        }
    }
    return updated
}
```

### Cienkie API ViewModel po refaktorze

```kotlin
// CapDetailViewModel.saveAssignment() — po refaktorze
private fun saveAssignment() {
    val pageId    = selectedPageId  ?: return
    val position  = selectedPosition ?: return
    val current   = capDetailUiState as? CapDetailUiState.Success ?: return
    val capId     = current.cap.id.toLong()
    viewModelScope.launch {
        isSaving = true
        assignmentError = null
        try {
            val snapshot = current.cap.toSnapshot()
            val newEntry = if (current.status == CollectionEntry.OwnershipState.IN_COLLECTION) {
                collectionRepository.recatalog(capId, pageId, position, snapshot)
            } else {
                collectionRepository.catalog(capId, pageId, position, snapshot)
            }
            val newBinderInfo = capPositionRepository.getBinderInfoByCapId(capId)
            capDetailUiState = current.copy(
                status = newEntry.state,
                binderInfo = newBinderInfo,
                cap = current.cap.copy(isInCollection = true)
            )
            binderSuggestion = null
        } catch (e: CollectionException.NotPurchased) {
            assignmentError = "Kapsel musi być najpierw oznaczony jako kupiony"
        } catch (e: CollectionException.ApiFailure) {
            assignmentError = "Błąd połączenia z API: ${e.message}"
        } catch (e: Exception) {
            assignmentError = "Nie udało się przypisać: ${e.message}"
            selectedPosition = null
        } finally {
            isSaving = false
        }
    }
}
```

```kotlin
// PurchasedViewModel — po refaktorze
init {
    viewModelScope.launch {
        collectionRepository.getPendingFlow().collect { pendingIds ->
            // Nie potrzeba odejmowania assignedIds — pending_cap (Room) jest jedynym źródłem prawdy
            _uiState.value = PurchasedUiState.Loading
            val caps = pendingIds.mapNotNull { id ->
                runCatching { capsRepository.getById(id.toInt()).toCap() }.getOrNull()
            }
            _uiState.value = PurchasedUiState.Success(caps)
        }
    }
}
```

---

## KROK 5 — Before/after, plan faz, testy

### Before / After dla każdego miejsca reguły

---

#### Miejsce 1: Definicja trójstanu

```kotlin
// BEFORE — CapDetailViewModel.kt:32
// enum prywatny, w warstwie UI
enum class CapStatus { IN_COLLECTION, PURCHASED, MISSING }

// AFTER — model/CollectionEntry.kt (warstwa domenowa)
// CollectionEntry.OwnershipState — publiczny, testowalny, bez zależności na Androida
enum class OwnershipState { MISSING, PURCHASED, IN_COLLECTION }
```

---

#### Miejsce 2: Inferowanie stanu przy getCap

```kotlin
// BEFORE — CapDetailViewModel.kt:229-241
val status = when {
    binderInfo != null -> CapStatus.IN_COLLECTION
    cap.isInCollection -> CapStatus.PURCHASED
    else -> CapStatus.MISSING
}
when (status) {
    CapStatus.PURCHASED -> purchasedCapsLocalStore.add(id.toLong())
    CapStatus.MISSING -> purchasedCapsLocalStore.remove(id.toLong())
    else -> Unit
}

// AFTER — CapDetailViewModel.getCap()
val entry = collectionRepository.get(id.toLong())
// collectionRepository.get() inferuje stan na podstawie pending_cap (Room) + cap_position (Room)
// + isInCollection (API); brak synchronizacji purchasedCapsLocalStore w ViewModel
```

---

#### Miejsce 3: Atomowość assign (kluczowa naprawa N-2)

```kotlin
// BEFORE — CapDetailViewModel.saveAssignment():162-169
// Brak transakcji; brak remove z purchasedCapsLocalStore
if (current.status == CapStatus.IN_COLLECTION) {
    capPositionRepository.reassign(capId, pageId, position, snapshot)
} else {
    if (!current.cap.isInCollection) {
        repository.addToCollection(current.cap.id)        // API
    }
    capPositionRepository.assign(pageId, position, capId, snapshot)  // Room insert
    // ❌ BRAK: purchasedCapsLocalStore.remove(capId)
}

// AFTER — CollectionRepositoryImpl.catalog() z db.withTransaction
db.withTransaction {
    pendingCapDao.deleteById(capId)    // usuń z pending_cap
    capPositionDao.insert(CapPosition(...))  // wstaw do cap_position
}
// Obie operacje Room w jednej transakcji ACID — niezmiennik N-2 egzekwowany strukturalnie
```

---

#### Miejsce 4: Kompensacja w PurchasedViewModel

```kotlin
// BEFORE — PurchasedViewModel.kt:48-49
// Dwa niezależne odczyty, brak transakcji, logika domenowa w UI
val assignedIds = capPositionRepository.getAllCapIds().toSet()
val purchasedIds = purchasedCapsLocalStore.getIds() - assignedIds

// AFTER — PurchasedViewModel.init
collectionRepository.getPendingFlow().collect { pendingIds ->
    // pending_cap (Room Flow) — reaktywny, spójny z cap_position dzięki transakcji catalog()
    // Nie potrzeba odejmowania — pending_cap nigdy nie zawiera capId z cap_position
}
```

---

#### Miejsce 5: Re-implementacja stanu w CapGridView

```kotlin
// BEFORE — CapGridView.kt:27-32
// Trzecia, niezależna re-implementacja trójstanu — inline w composable
val assignedCapIds = LocalAssignedCapIds.current
val border = when {
    !cap.isInCollection -> null
    cap.id in assignedCapIds -> BorderStroke(6.dp, Color(0xFF00FF00))
    else -> BorderStroke(6.dp, Color(0xFF2196F3))
}

// AFTER — bez zmian strukturalnych w CapGridView
// CapGridView pozostaje bez zmian — renderuje na podstawie cap.isInCollection (API)
// i assignedCapIds (Room). Ta logika jest POPRAWNA jako rendering layer i nie narusza
// niezmiennika: oba źródła są read-only w composable.
// Spójność gwarantuje CollectionRepository, a nie widok.
```

---

#### Miejsce 6: PendingCapRepository — aktywacja zamiast usunięcia

```kotlin
// BEFORE — PendingCapRepository.kt istnieje, zero konsumentów w ViewModelach
// SharedPreferences (PurchasedCapsLocalStore) jest faktycznym store'em PURCHASED state

// AFTER — PendingCapRepository staje się podstawą CollectionRepositoryImpl
// PurchasedCapsLocalStore usuwany po migracji wszystkich konsumentów
```

---

### Plan faz refaktoru

#### Faza 1 — Model domenowy (test-first) ✦ bez zmian w istniejącym kodzie

| Krok | Plik | Opis |
|------|------|------|
| 1a | `model/CollectionEntry.kt` | Nowy agregat z `OwnershipState` i metodami domenowymi |
| 1b | `model/CollectionException.kt` | Sealed class błędów domenowych |
| 1c | `app/src/test/.../model/CollectionEntryTest.kt` | 11 przypadków testowych (tabela poniżej) |

**Warunek zaliczenia fazy 1:** `./gradlew test` — wszystkie nowe testy zielone.

---

#### Faza 2 — CollectionRepository (test-first)

| Krok | Plik | Opis |
|------|------|------|
| 2a | `data/CollectionRepository.kt` | Interface z sygnaturami |
| 2b | `data/CollectionRepositoryImpl.kt` | Implementacja z `db.withTransaction` w `catalog()` i `uncatalog()` |
| 2c | `di/CollectionModule.kt` | Hilt binding interface → implementacja |
| 2d | `app/src/test/.../data/CollectionRepositoryTest.kt` | Testy z mockk: atomowość catalog(), sekwencja markBought() |

**Warunek zaliczenia fazy 2:** testy `CollectionRepositoryTest` przechodzą; żaden istniejący test nie jest złamany.

---

#### Faza 3 — Migracja ViewModeli

| Krok | Plik | Opis |
|------|------|------|
| 3a | `CapDetailViewModel.kt` | Wstrzyknij `CollectionRepository`; usuń bezpośrednie wstrzykiwania `PurchasedCapsLocalStore`; zastąp `saveAssignment()`, `setStatus()`, `getCap()` wywołaniami CollectionRepository |
| 3b | `PurchasedViewModel.kt` | Wstrzyknij `CollectionRepository`; zastąp `purchasedCapsLocalStore.getIds() - assignedIds` wywołaniem `getPendingFlow()` |
| 3c | `enum class CapStatus` | Usuń z `CapDetailViewModel.kt:32`; zastąp `CollectionEntry.OwnershipState` |

**Warunek zaliczenia fazy 3:** `./gradlew build` zielony; manualna weryfikacja flow zakup → katalogowanie.

---

#### Faza 4 — Usunięcie martwego kodu

| Krok | Opis |
|------|------|
| 4a | Zweryfikuj: `grep -r "PurchasedCapsLocalStore"` — tylko definicja klasy |
| 4b | Usuń `PurchasedCapsLocalStore.kt` i binding w module Hilt |
| 4c | Zachowaj `PendingCapRepository.kt` — jest teraz aktywnie używany przez `CollectionRepositoryImpl` |

**Warunek zaliczenia fazy 4:** `./gradlew build` zielony; `grep -r "PurchasedCapsLocalStore"` → 0 wyników.

---

### Przypadki testowe dla niezmiennika (test-first, Faza 1 + 2)

#### CollectionEntryTest — model domenowy (host JVM, brak Androida)

| # | Stan wejściowy | Operacja | Oczekiwany wynik |
|---|----------------|----------|-----------------|
| T-01 | MISSING | `markBought()` | Returns entry ze stanem PURCHASED |
| T-02 | PURCHASED | `markBought()` | Rzuca `CollectionException.AlreadyOwned` |
| T-03 | IN_COLLECTION | `markBought()` | Rzuca `CollectionException.AlreadyOwned` |
| T-04 | PURCHASED | `catalog()` | Returns entry ze stanem IN_COLLECTION |
| T-05 | MISSING | `catalog()` | Rzuca `CollectionException.NotPurchased` |
| T-06 | IN_COLLECTION | `catalog()` | Rzuca `CollectionException.NotPurchased` |
| T-07 | IN_COLLECTION | `uncatalog()` | Returns entry ze stanem PURCHASED |
| T-08 | MISSING | `uncatalog()` | Rzuca `CollectionException.NotAssigned` |
| T-09 | PURCHASED | `removeFromCollection()` | Returns entry ze stanem MISSING |
| T-10 | IN_COLLECTION | `removeFromCollection()` | Returns entry ze stanem MISSING |
| T-11 | MISSING | `removeFromCollection()` | Rzuca `CollectionException.NotOwned` |

#### CollectionRepositoryTest — integracja z mockowanymi DAO (mockk + runTest)

| # | Scenariusz | Weryfikacja |
|---|------------|-------------|
| T-12 | `catalog()` gdy cap PURCHASED i API zwraca sukces | `pendingCapDao.deleteById(capId)` wywołany; `capPositionDao.insert()` wywołany; oba w tej samej transakcji `db.withTransaction` |
| T-13 | `catalog()` gdy cap MISSING | Rzuca `CollectionException.NotPurchased` przed dotknięciem bazy danych — `pendingCapDao` nie wywołany |
| T-14 | `markBought()` gdy API zwraca błąd HTTP | Rzuca `CollectionException.ApiFailure`; `pendingCapDao.insert()` nie wywołany |
| T-15 | `catalog()` gdy cap PURCHASED i API zwraca błąd | Rzuca `CollectionException.ApiFailure`; baza danych nienaruszona |
| T-16 | `uncatalog()` gdy cap IN_COLLECTION | `capPositionDao.deleteByCapId(capId)` wywołany; `pendingCapDao.insert()` wywołany w tej samej transakcji |

---

### Nowe load-bearing nazwy do rejestracji

> Jeśli projekt prowadzi rejestr kontraktów (`docs/reference/contract-surfaces.md`):

| Nazwa | Typ | Lokalizacja (docelowa) | Zastępuje |
|-------|-----|------------------------|-----------|
| `CollectionEntry` | data class (aggregate root) | `model/CollectionEntry.kt` | `enum class CapStatus` w `CapDetailViewModel.kt:32` |
| `CollectionEntry.OwnershipState` | enum (domain model) | `model/CollectionEntry.kt` | `CapStatus` |
| `CollectionException` | sealed class | `model/CollectionException.kt` | generyczne wyjątki + ciche ignorowanie |
| `CollectionRepository` | interface | `data/CollectionRepository.kt` | bezpośredni dostęp do `PurchasedCapsLocalStore` + `CapPositionRepository` z ViewModeli |
| `CollectionRepositoryImpl` | class | `data/CollectionRepositoryImpl.kt` | — |
| `CollectionModule` | Hilt module | `di/CollectionModule.kt` | — |

---

## Podsumowanie

Wybrany niezmiennik — **trójstanowy model posiadania kapsla (MISSING / PURCHASED / IN_COLLECTION)** — jest
jednocześnie najbardziej rdzeniowym (odpowiada bezpośrednio na North Star produktu) i najsłabiej
egzekwowanym: jego jedyna formalna definicja (`enum class CapStatus`) żyje wyłącznie w warstwie UI
(`CapDetailViewModel.kt:32`), a przejście `PURCHASED → IN_COLLECTION` nie ma transakcji Room łączącej
usunięcie z `pending_cap` i wstawienie do `cap_position`. Leakage w `saveAssignment()` (brak
`purchasedCapsLocalStore.remove()`) powoduje, że kapsel może być jednocześnie w obu stanach lokalnych,
a jedyną ochroną jest kompensacja obliczeniowa w `PurchasedViewModel.kt:48-49`. Plan czterofazowego
refaktoru (model domenowy → CollectionRepository z atomową transakcją → migracja ViewModeli → usunięcie
martwego kodu) usuwa wyciek logiki do UI, aktywuje istniejący `PendingCapRepository` (Room) jako jedyne
źródło prawdy dla stanu PURCHASED, a `PurchasedCapsLocalStore` (SharedPreferences) likwiduje całkowicie.
Fazy 1 i 2 są test-first: łącznie 16 przypadków testowych weryfikuje zarówno model domenowy (legalne i
nielegalne przejścia stanów), jak i atomowość operacji repozytorium.

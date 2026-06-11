# Binder Management — Plan implementacji

## Overview

Nowy ekran `BindersScreen` z listą klaserów — każdy rozwijany inline pokazuje strony.
Użytkownik może tworzyć klasery, dodawać strony (limit 15) i usuwać każdy element przez
dialog potwierdzenia. Wejście z `HomeScreen` przez nowy `NavigationItem`.

## Current State Analysis

- **Repozytoria gotowe**: `BinderRepository.create/delete`, `BinderPageRepository.addPage/deletePage` — F-02/F-03
- **Brak**: jakiegokolwiek ekranu/ViewModel dla klaserów; brak trasy w `Screen.kt`
- **HomeScreen**: `ui/home/HomeScreen.kt:52–63` — kolumna `NavigationItem`-ów; wzorzec do powielenia
- **NavigationItem**: zdefiniowany w `MainActivity.kt`; przyjmuje `text`, `icon`, `onClick`
- **NavHost**: `MainActivity.kt` — każdy ekran to `composable(Screen.X.route) { ... }` + `hiltViewModel<>()`
- **Wzorzec ViewModel**: `@HiltViewModel`, `StateFlow<UiState>`, bez `PagingData` (to ekran CRUD, nie listujący API)
- **Jedyny komponent współdzielony**: `FullSizeLoader.kt` — brak gotowego `ConfirmDialog` ani `EmptyState`

## Desired End State

Po ukończeniu obu faz:
- HomeScreen ma wpis "Klasery" nawigujący do `BindersScreen`
- `BindersScreen` wyświetla listę klaserów; klaser rozwijany pokazuje jego strony
- FAB otwiera dialog tworzenia klasera (walidacja: niepuste)
- Każdy klaser i strona mają przycisk usuwania → AlertDialog potwierdzenia
- Zajęty klaser (z kapslami) → błąd w Snackbar; 16. strona → błąd w Snackbar
- BindersViewModelTest (JVM) pokrywa create/delete/addPage scenariusze

### Key Discoveries

- `HomeScreen.kt:53–62` — 3 istniejące NavigationItem-y; nowy wpis "Klasery" dokładamy na końcu
- `Screen.kt` — sealed class, nowy wpis jako `object Binders : Screen("binders")`
- `BinderPageRepository.getByBinder(binderId): Flow<List<BinderPage>>` — Flow subskrybowany leniwie per binder gdy rozwinięty
- `BinderRepository.delete()` rzuca `IllegalStateException` gdy klaser zajęty — ViewModel łapie i emituje Snackbar event
- `BinderPageRepository.addPage()` rzuca `IllegalStateException` gdy >15 stron — j.w.
- Brak Room constraint na liczbę stron — guard w `BinderPageRepository.addPage()`

## Desired End State

Weryfikacja manualna: HomeScreen → "Klasery" → lista → utwórz → rozwiń → dodaj stronę → usuń stronę → usuń klaser (pusty i zajęty).

## What We're NOT Doing

- Nie budujemy osobnego ekranu detalu klasera (S-03 rozszerzy ten widok)
- Nie implementujemy edycji nazwy klasera (poza zakresem S-02)
- Nie dodajemy Bottom Navigation Bar (globalna nawigacja to osobna decyzja)
- Nie testujemy UI Compose (ViewModel test JVM wystarczy dla S-02)
- Nie walidujemy formatu "Kontynent + numer" — wolny tekst, tylko niepuste

## Implementation Approach

Dwie fazy: najpierw wbijamy trasy, ViewModel i wejście z HomeScreen (Phase 1), potem piszemy pełne UI i testy (Phase 2). Podział pozwala zweryfikować nawigację przed implementacją wszystkich dialógów.

**UiState contract** (Phase 1 definiuje, Phase 2 konsumuje):

```kotlin
data class BindersUiState(
    val binders: List<Binder> = emptyList(),
    val expandedBinderIds: Set<Long> = emptySet(),
    val binderPages: Map<Long, List<BinderPage>> = emptyMap(),
    val isCreateDialogOpen: Boolean = false,
    val deleteBinderConfirmId: Long? = null,
    val deletePageConfirmId: Long? = null
)

sealed interface BindersEvent {
    data class ShowSnackbar(val message: String) : BindersEvent
}
```

Events (Snackbar) przepływają przez `Channel<BindersEvent>(BUFFERED)` → `receiveAsFlow()`.
Strony subskrybowane leniwie: przy pierwszym `toggleExpand(binderId)` ViewModel startuje
coroutine zbierający `binderPageRepository.getByBinder(binderId)` i aktualizuje `binderPages`.

---

## Phase 1: Nawigacja + ViewModel

### Overview

Dodanie trasy, wejścia z HomeScreen, szkieletu `BindersScreen` (placeholder) i pełnego
`BindersViewModel` z logiką biznesową i UiState.

### Changes Required:

#### 1. `Screen.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/navigation/Screen.kt`

**Intent**: Dodaj trasę dla ekranu klaserów.

**Contract**: `object Binders : Screen("binders")`

#### 2. `HomeScreen.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/ui/home/HomeScreen.kt`

**Intent**: Dodaj punkt wejścia do klaserów na liście kafelków nawigacyjnych.

**Contract**: Nowy `NavigationItem` po `Countries`, ikona `Icons.Filled.FolderOpen`,
tekst "Klasery", `onClick { onClick(Screen.Binders) }`. Parametr `onClick: (Screen) -> Unit`
już istnieje w sygnaturze `HomeScreen`.

#### 3. `MainActivity.kt`

**File**: `app/src/main/java/pl/sroki/cci/android/MainActivity.kt`

**Intent**: Zarejestruj trasę Binders w NavHost.

**Contract**: `composable(route = Screen.Binders.route) { BindersScreen(onBack = { navController.popBackStack() }) }`

#### 4. `BindersViewModel.kt` (nowy plik)

**File**: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersViewModel.kt`

**Intent**: ViewModel zarządzający pełnym stanem ekranu klaserów — listą, rozwinięciami,
stronami, dialogami i błędami.

**Contract**: `@HiltViewModel class BindersViewModel @Inject constructor(private val binderRepository: BinderRepository, private val binderPageRepository: BinderPageRepository) : ViewModel()`.
Eksponuje:
- `val uiState: StateFlow<BindersUiState>`
- `val events: Flow<BindersEvent>` (Channel-backed)
- Metody: `toggleExpand(binderId)`, `showCreateDialog()`, `dismissCreateDialog()`,
  `createBinder(name: String)`, `requestDeleteBinder(binderId)`, `confirmDeleteBinder()`,
  `dismissDeleteBinder()`, `addPage(binderId)`, `requestDeletePage(pageId)`,
  `confirmDeletePage()`, `dismissDeletePage()`

`createBinder` i `confirmDeleteBinder` / `confirmDeletePage` / `addPage` uruchamiane przez
`viewModelScope.launch`; wyjątki `IllegalStateException` i `IllegalArgumentException`
łapane → `_events.send(BindersEvent.ShowSnackbar(e.message ?: "Błąd"))`.

#### 5. `BindersScreen.kt` (stub)

**File**: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersScreen.kt`

**Intent**: Placeholder żeby Phase 1 się kompilowała i nawigacja działała.

**Contract**: `@Composable fun BindersScreen(onBack: () -> Unit)` — `Scaffold` z `TopAppBar`
(tytuł "Klasery", przycisk Back) i pustym `Box` w treści. `hiltViewModel<BindersViewModel>()`
wołany ale UiState ignorowany do Phase 2.

### Success Criteria:

#### Automated Verification:

- Kompilacja: `./gradlew :app:compileDebugKotlin`
- KSP (Hilt): `./gradlew :app:kspDebugKotlin`
- Ktlint: `./gradlew :app:ktlintCheck`

#### Manual Verification:

- Aplikacja uruchamia się bez crasha
- HomeScreen → widać wpis "Klasery"
- Kliknięcie "Klasery" otwiera pusty ekran z nagłówkiem "Klasery" i przyciskiem Back
- Back wraca do HomeScreen

---

## Phase 2: Pełne UI + testy

### Overview

Implementacja `BindersScreen` z expandable rows, dialogami create/delete i Snackbar.
Testy jednostkowe ViewModel na JVM.

### Changes Required:

#### 1. `BindersScreen.kt` (pełna implementacja)

**File**: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersScreen.kt`

**Intent**: Pełny ekran zarządzania klaserami — lista, rozwinięcia, dialogi, Snackbar.

**Contract**:
- `Scaffold` z `TopAppBar` + `FloatingActionButton` (ikona Add, onClick → `vm.showCreateDialog()`)
- `SnackbarHost` w Scaffold konsumuje `vm.events.collectAsEffect { ... }` — pattern: `LaunchedEffect(Unit) { vm.events.collect { e -> when(e) { is ShowSnackbar -> snackbarState.showSnackbar(e.message) } } }`
- `LazyColumn` ze stanem z `vm.uiState.collectAsState()`
- Każdy element listy: `ExpandableBinderRow(binder, expanded, pages, onToggle, onDeleteBinder, onAddPage, onDeletePage)`
  - Nagłówek: nazwa klasera + ikona Expand + IconButton Delete (→ `vm.requestDeleteBinder(id)`)
  - Rozwinięty: lista stron (numer + IconButton Delete) + TextButton "Dodaj stronę" (→ `vm.addPage(id)`)
- **Dialog tworzenia**: `if (uiState.isCreateDialogOpen)` → `AlertDialog` z `TextField` dla nazwy + Zapisz/Anuluj
  - Zapisz wołuje `vm.createBinder(name)` tylko gdy name.isNotBlank()
- **Dialog usuwania klasera**: `if (uiState.deleteBinderConfirmId != null)` → `AlertDialog` "Usuń klaser?" + Usuń/Anuluj
- **Dialog usuwania strony**: `if (uiState.deletePageConfirmId != null)` → `AlertDialog` "Usuń stronę?" + Usuń/Anuluj

#### 2. `BindersViewModelTest.kt` (nowy plik)

**File**: `app/src/test/java/pl/sroki/cci/android/ui/binders/BindersViewModelTest.kt`

**Intent**: JVM unit testy logiki ViewModel — tworzenie/usuwanie klasera i dodawanie strony.

**Contract**: `@OptIn(ExperimentalCoroutinesApi::class)` + `TestCoroutineScheduler` +
`Dispatchers.setMain(UnconfinedTestDispatcher())`. Mockk do mockowania repozytoriów.
Testy:
- `createBinder_updatesState`: `every { binderRepository.getAll() } returns flowOf(listOf(binder))`; po `createBinder("Europa 1")` — `uiState.binders` zawiera klaser
- `createBinder_blankName_showsSnackbar`: pusta nazwa → ViewModel łapie exception → event `ShowSnackbar`
- `deleteBinder_occupied_showsSnackbar`: `confirmDeleteBinder()` gdy repo rzuca `IllegalStateException` → `ShowSnackbar`
- `addPage_atLimit_showsSnackbar`: `addPage(binderId)` gdy repo rzuca `IllegalStateException` → `ShowSnackbar`
- `requestDeleteBinder_setsConfirmId`: po `requestDeleteBinder(42L)` → `uiState.deleteBinderConfirmId == 42L`

### Success Criteria:

#### Automated Verification:

- Kompilacja: `./gradlew :app:compileDebugKotlin`
- Testy JVM: `./gradlew :app:testDebugUnitTest`
- Ktlint: `./gradlew :app:ktlintCheck`

#### Manual Verification:

- Tworzenie klasera: FAB → dialog → wpisz nazwę → Zapisz → klaser pojawia się na liście
- Walidacja pustej nazwy: Zapisz bez tekstu → nic się nie dzieje (przycisk Zapisz nieaktywny lub Snackbar)
- Rozwinięcie klasera: kliknięcie → strony widoczne (puste przy nowym klaserze)
- Dodaj stronę: kliknięcie "Dodaj stronę" → strona pojawia się (np. "Strona 1")
- Usuwanie strony: trash → dialog → Usuń → strona znika
- Usuwanie pustego klasera: trash → dialog → Usuń → klaser znika
- Usuwanie zajętego klasera: (brak UI przypisania kapsla w S-02, ale) Snackbar z komunikatem błędu gdy repo odrzuci
- 16. strona: po 15 stronach kliknięcie "Dodaj stronę" → Snackbar "Klaser może mieć maksymalnie 15 stron"

---

## Testing Strategy

### Unit Tests (JVM):

- `BindersViewModelTest` — 5 testów pokrywających: poprawne tworzenie, pusta nazwa, zajęty klaser, limit stron, confirm-id w state

### Manual Testing Steps:

1. HomeScreen → "Klasery" — nawigacja działa
2. Utwórz 3 klasery — widoczne na liście
3. Rozwiń każdy — lista stron pusta
4. Dodaj 3 strony do jednego klasera — Strona 1, 2, 3 widoczne
5. Usuń stronę — dialog, po potwierdzeniu znika
6. Spróbuj dodać 16. stronę — Snackbar
7. Usuń pusty klaser — dialog, po potwierdzeniu znika
8. Back → HomeScreen

## References

- Roadmap: `context/foundation/roadmap.md` — S-02
- Repozytoria: `data/BinderRepository.kt`, `data/BinderPageRepository.kt`
- Wzorzec HomeScreen: `ui/home/HomeScreen.kt:52–63`
- Wzorzec ViewModel: `ui/catalog/latest/LatestCapsViewModel.kt`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Nawigacja + ViewModel

#### Automated

- [x] 1.1 Kompilacja: `./gradlew :app:compileDebugKotlin` — 8246197
- [x] 1.2 KSP (Hilt): `./gradlew :app:kspDebugKotlin` — 8246197
- [x] 1.3 Ktlint: `./gradlew :app:ktlintCheck` — 8246197

#### Manual

- [x] 1.4 Aplikacja uruchamia się bez crasha — 8246197
- [x] 1.5 HomeScreen → wpis "Klasery" widoczny — 8246197
- [x] 1.6 Nawigacja do BindersScreen (stub) działa, Back wraca — 8246197

### Phase 2: Pełne UI + testy

#### Automated

- [x] 2.1 Kompilacja: `./gradlew :app:compileDebugKotlin`
- [x] 2.2 Testy JVM: `./gradlew :app:testDebugUnitTest`
- [x] 2.3 Ktlint: `./gradlew :app:ktlintCheck`

#### Manual

- [x] 2.4 Tworzenie klasera przez FAB + dialog
- [x] 2.5 Rozwijanie/zwijanie klasera, widok stron
- [x] 2.6 Dodawanie strony, limit 15 → Snackbar
- [x] 2.7 Usuwanie strony i klasera przez dialog potwierdzenia
- [x] 2.8 Brak crasha / ANR przy wszystkich operacjach

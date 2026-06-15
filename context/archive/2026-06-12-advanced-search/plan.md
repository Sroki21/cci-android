# Advanced Search — Implementation Plan

## Overview

Nowy ekran szukania zaawansowanego: formularz filtrów (ID, Tekst, Kraj, Producent
z operatorami) + paginowane wyniki poniżej. Dostępny z HomeScreen po odblokowaniu
wyłączonego przycisku.

## Current State Analysis

- `CapApiService.getByQuery()` — `GET api/v1/caps?query=` — wolny tekst, 20/stronę
- `CapApiService.getById()` — `GET api/v1/caps/{id}` — jeden kapsel po ID
- `CountriesRepository.getCountries()` — istnieje; lista krajów z API
- `QuickSearchViewModel` — wzorzec Pager z `pagingSourceFactory` + `collectionChanged`
- `Screen` — sealed class w `navigation/Screen.kt`; brak `AdvancedSearch`
- `HomeScreen` — przycisk „Szukanie zaawansowane" z `enabled = false`

## Desired End State

1. Tap „Szukanie zaawansowane" na HomeScreen → nawigacja do `AdvancedSearchScreen`
2. Ekran: na górze formularz filtrów (zwijany?), pod nim LazyColumn wyników
3. Formularz: 4 sekcje filtrów, każda z dropdownem operatora i polem wejściowym
4. Kraj: autocomplete — tap otwiera dialog/bottomsheet z listą krajów (załadowanych raz przy wejściu na ekran)
5. „Szukaj" → ViewModel konstruuje zapytanie, Pager ładuje wyniki
6. Wyniki: `CapListItem` identyczny jak w QuickSearch; tap → `CapDetailScreen`

## What We're NOT Doing

- Operator-level server filtering (nieznana obsługa API — implementujemy best-effort)
- Kategorie jako filtr
- Sortowanie
- Zachowanie stanu filtrów po powrocie

---

## Phase 1: Data layer + model filtrów

### 1.1 `SearchOperator` + `AdvancedSearchFilter`

**File**: `app/src/main/java/pl/sroki/cci/android/model/AdvancedSearch.kt` (nowy)

```kotlin
enum class SearchOperator(val label: String) {
    CONTAINS("Zawiera"),
    EQUALS("Równe"),
    STARTS_WITH("Zaczyna się od")
}

data class AdvancedSearchFilter(
    val idValue: String = "",
    val idOperator: SearchOperator = SearchOperator.EQUALS,
    val textValue: String = "",
    val textOperator: SearchOperator = SearchOperator.CONTAINS,
    val countryId: Int? = null,
    val countryName: String = "",
    val producerValue: String = "",
    val producerOperator: SearchOperator = SearchOperator.CONTAINS,
    val onlyInCollection: Boolean = false
) {
    fun isEmpty() = idValue.isBlank() && textValue.isBlank()
        && countryId == null && producerValue.isBlank() && !onlyInCollection
}
```

### 1.2 Nowy endpoint w `CapApiService`

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/CapApiService.kt`

Dodać po istniejących metodach:

```kotlin
@GET("api/v1/caps")
suspend fun advancedSearch(
    @Query("query") query: String? = null,
    @Query("country_id") countryId: Int? = null,
    @Query("producer") producer: String? = null,
    @Query("in_collection") inCollection: Int? = null,
    @Query("page") page: Int,
    @Query("perPage") perPage: Int
): Page<Cap>
```

**Uwaga**: Parametry `country_id`, `producer` i `in_collection` są optymistyczne —
nieznana obsługa API. Parametry niezrozumiane przez serwer są ignorowane.
Weryfikacja po deploymencie. `in_collection=1` widoczny tylko dla zalogowanych.

### 1.3 `AdvancedSearchPagingSource` + metoda w `CapsRepository`

**File**: `app/src/main/java/pl/sroki/cci/android/data/AdvancedSearchPagingSource.kt` (nowy)

Wzorzec identyczny jak `QuickSearchPagingSource`. Konstruktor przyjmuje
`CapApiService` + `AdvancedSearchFilter`. Logika `load()`:

- Jeśli `filter.idOperator == EQUALS` i `filter.idValue` jest liczbą całkowitą:
  wywołaj `capApiService.getById(id)` i owiń w `Page` z pustą paginacją
  (LoadResult.Page z `prevKey = null`, `nextKey = null`).
- W przeciwnym razie: zbuduj `query` jako złączenie aktywnych pól tekstowych
  (idValue, textValue, producerValue) z prefiksami jeśli STARTS_WITH
  (np. prefix '^' — zależy od API; na start: bez prefiksu), wywołaj
  `capApiService.advancedSearch(query, countryId, producer,
   inCollection = if (filter.onlyInCollection) 1 else null, page, perPage)`.
- Pierwsza strona (`page == 1`) zapisuje `page.total` przez callback do ViewModelu
  (SharedFlow lub State).

**Metoda w `CapsRepository`**:

```kotlin
fun advancedSearchPagingSource(filter: AdvancedSearchFilter): PagingSource<Int, Cap> =
    AdvancedSearchPagingSource(capApiService, filter)
```

---

## Phase 2: ViewModel + Screen + Navigation

### 2.1 `AdvancedSearchViewModel`

**File**: `app/src/main/java/pl/sroki/cci/android/ui/catalog/caps/advanced/AdvancedSearchViewModel.kt` (nowy)

```kotlin
@HiltViewModel
class AdvancedSearchViewModel @Inject constructor(
    private val capsRepository: CapsRepository,
    private val countriesRepository: CountriesRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    var filter by mutableStateOf(AdvancedSearchFilter())
        private set

    var countries by mutableStateOf<List<Country>>(emptyList())
        private set

    var totalResults by mutableStateOf<Int?>(null)
        private set

    val isLoggedIn: StateFlow<Boolean> = sessionRepository.isLoggedIn

    private var _searched = false
    val hasSearched: Boolean get() = _searched

    private var pagingSource: PagingSource<Int, Cap>? = null
    private val _filterTrigger = MutableStateFlow(0)

    val caps: Flow<PagingData<Cap>> = _filterTrigger
        .flatMapLatest {
            if (!_searched) flowOf(PagingData.empty())
            else Pager(
                config = PagingConfig(pageSize = Cap.PER_PAGE),
                pagingSourceFactory = {
                    capsRepository.advancedSearchPagingSource(filter).also { pagingSource = it }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            capsRepository.collectionChanged.collect { pagingSource?.invalidate() }
        }
        viewModelScope.launch {
            countries = try { countriesRepository.getCountries() } catch (e: Exception) { emptyList() }
        }
    }

    fun updateFilter(updated: AdvancedSearchFilter) { filter = updated }

    fun onTotalLoaded(total: Int) { totalResults = total }

    fun search() {
        _searched = true
        totalResults = null
        _filterTrigger.value++
    }
}
```

### 2.2 `AdvancedSearchScreen`

**File**: `app/src/main/java/pl/sroki/cci/android/ui/catalog/caps/advanced/AdvancedSearchScreen.kt` (nowy)

Layout:

```
Scaffold(TopAppBar = "Szukanie zaawansowane" + back)
  Column(fillMaxSize, verticalScroll = disabled — LazyColumn handles scroll)
    FilterForm(filter, countries, onFilterChange, onSearch)
    Divider
    if (hasSearched) CapResultsList(caps, onCapClick)
    else EmptyPrompt("Wypełnij filtry i naciśnij Szukaj")
```

`FilterForm`: `Column` z 4 `FilterRow` + checkbox + przycisk:
- Każdy `FilterRow`: `Row` z `OperatorDropdown` (compact, ~110dp) + `OutlinedTextField` lub `CountryPickerField`
- `CountryPickerField`: `OutlinedTextField(readOnly=true)` z tapem otwierającym `AlertDialog` z `LazyColumn` krajów + pole wyszukiwania w dialogu
- `Row` z `Checkbox` + `Text("Tylko kapsle w kolekcji")`; widoczny tylko gdy `isLoggedIn == true`
- Przycisk „Szukaj" na dole formularza; disabled jeśli `filter.isEmpty()`

Po wynikach, bezpośrednio pod formularzem (gdy `hasSearched`):
- `Text("Znaleziono: ${totalResults ?: "…"} kapsli")` — `totalResults` aktualizowany
  przez `AdvancedSearchPagingSource` po załadowaniu pierwszej strony (callback `onTotalLoaded`)

`CapResultsList`: `LazyColumn` z `collectAsLazyPagingItems()` — identyczny wzorzec jak `QuickSearchScreen`; każdy item → `CapListItem` (reuse istniejący composable jeśli dostępny)

### 2.3 `Screen.AdvancedSearch` + nawigacja

**File**: `app/src/main/java/pl/sroki/cci/android/navigation/Screen.kt`

```kotlin
object AdvancedSearch : Screen("advanced-search")
```

**File**: `app/src/main/java/pl/sroki/cci/android/MainActivity.kt`

Dodać route:
```kotlin
composable(Screen.AdvancedSearch.route) {
    AdvancedSearchScreen(onBack = { navController.popBackStack() },
                        onCapClick = { navController.navigate(Screen.CapDetail.createUrl(it)) })
}
```

**File**: `app/src/main/java/pl/sroki/cci/android/ui/home/HomeScreen.kt`

Zmienić disabled button:
```kotlin
NavigationItem(text = "Szukanie zaawansowane", icon = Icons.Filled.FilterList) {
    onClick(Screen.AdvancedSearch)
}
```

---

## Success Criteria

### Automated
- Kompilacja: `./gradlew :app:compileDebugKotlin`
- Testy: `./gradlew :app:testDebugUnitTest`

### Manual
- Tap „Szukanie zaawansowane" na HomeScreen → otwiera AdvancedSearchScreen
- Formularz wyświetla 4 filtry z dropdownami operatora
- Kraj: tap → dialog z listą krajów; wybór wypełnia pole
- Przycisk „Szukaj" nieaktywny gdy wszystkie pola puste
- Wpisanie tekstu + „Szukaj" → wyniki poniżej formularza
- Wpisanie poprawnego ID + operator „Równe" + „Szukaj" → jeden wynik (kapsel o danym ID)
- Kombinacja Tekst + Kraj → wyniki (weryfikacja czy API filtruje po obu)
- Tap na kapsel → otwiera `CapDetailScreen`
- Licznik „Znaleziono: N kapsli" pojawia się po pierwszym załadowaniu wyników
- Checkbox „Tylko kapsle w kolekcji" widoczny tylko gdy zalogowany; zaznaczenie + Szukaj filtruje wyniki
- Back → powrót do HomeScreen

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done.

### Phase 1: Data layer

- [ ] 1.1 `SearchOperator` + `AdvancedSearchFilter` w `model/AdvancedSearch.kt`
- [ ] 1.2 `advancedSearch()` endpoint w `CapApiService`
- [ ] 1.3 `AdvancedSearchPagingSource` + metoda w `CapsRepository`

### Phase 2: ViewModel + Screen + Navigation

- [ ] 2.1 `AdvancedSearchViewModel`
- [ ] 2.2 `AdvancedSearchScreen` (FilterForm + CapResultsList)
- [ ] 2.3 `Screen.AdvancedSearch` + route w `MainActivity` + odblokowanie HomeScreen

### Verification

- [ ] V.1 Kompilacja bez błędów
- [ ] V.2 Testy jednostkowe przechodzą
- [ ] V.3 Manual gate (lista z Success Criteria powyżej)

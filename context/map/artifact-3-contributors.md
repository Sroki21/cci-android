# Artifact 3 — Contributors (Kontekst kontrybutorów)

> Raport roboczy. Projekt nie posiada repozytorium git — brak historii
> commitów, blame ani statystyk aktywności. Wnioski opierają się na
> sygnałach w kodzie (namespace, nazwy, styl, TODOs).

---

## 1. Identyfikacja autora

### Jedyny widoczny sygnał autorstwa

Package root: `pl.sroki.cci.android`

Człon `sroki` to prawdopodobnie pseudonim lub login autora
(imię „Slava Bogov" lub podobne). Aplikacja jest publikowana/rozwijana
przez tę osobę pod domeną `crowncaps.info`.

### Wniosek

Projekt wygląda na pracę **jednego dewelopera** (prawdopodobnie
właściciela/operatora serwisu CrowCaps.Info):
- Brak dywersyfikacji stylu kodu.
- Spójne podejście do ViewModeli (ten sam wzorzec `mutableStateOf` + sealed interface).
- Brak recenzji (TODOs pozostają, dead code nie jest usuwany).
- Literówka w nazwie pliku (`.kt.kt`) wskazuje brak code review.

---

## 2. Profil wiedzy i doświadczenia (wnioskowane)

### Mocne strony widoczne w kodzie

| Obszar | Dowód |
|---|---|
| Jetpack Compose | Spójne użycie Scaffold, LazyColumn/LazyVerticalGrid, Pager, hiltViewModel, collectAsLazyPagingItems |
| Hilt DI | Poprawnie skonfigurowany `NetworkModule`, `@HiltViewModel`, `@AndroidEntryPoint` |
| Paging 3 | 4 oddzielne `PagingSource` z poprawną logiką `prevKey`/`nextKey` |
| Architektura MVVM | Czytelna separacja Screen → ViewModel → Repository → ApiService |
| Kotlinx serialization | Użycie `@Serializable`, `@SerialName`, obsługa `Instant` przez własny serializer |

### Obszary wymagające uwagi / luki

| Obszar | Dowód |
|---|---|
| Czyszczenie dead code | Orphan search flow (`SearchRepo`, `Search.kt`), martwa `CountriesActivity` w manifeście |
| Wzorzec mutowalnych pól VM | `viewModel.query = query` po inicjalizacji VM — ryzyko stale data przy odtworzeniu |
| Konwencje pakietów | `HomeScreen.kt` w złym pakiecie (`ui` zamiast `ui.home`) |
| Obsługa błędów | Większość ekranów obsługuje błąd przez `Text("Error")` bez retry |
| Brak testów | `test/` i `androidTest/` istnieją jako katalogi, ale bez zawartości — brak nawet szkieletowych testów |
| Brak offline/cache | Żaden mechanizm lokalnego cache — aplikacja wymaga połączenia |

---

## 3. Obszary wymagające wsparcia / transferu wiedzy

Dla nowego kontrybutora wchodzącego do projektu, najważniejsze do
zrozumienia (od trudniejszych do prostszych):

### Poziom 1 — Trudne (wymagają dogłębnej analizy)

1. **Paginacja (Paging 3)**: Cztery `PagingSource` + cztery `Pager` w
   ViewModelach + `collectAsLazyPagingItems()` w Screens. Konfiguracja
   `pageSize = Cap.PER_PAGE = 60`. Logika `prevKey`/`nextKey` oparta na
   `pagination.currentPage` vs `pagination.lastPage` z API.

2. **Dwuetapowy flow PictureSearch**: Ekran 1 (`PictureSearchScreen`) → VM
   `PictureSearchViewModel` ← `CategoriesRepository` ← `CategoryApiService`.
   Ekran 2 (`PictureSearchCapsScreen`) → VM `PictureSearchCapsViewModel` ←
   `CapsRepository`. Oba ekrany połączone przez nawigację z przekazaniem
   `Set<Category>` jako listy `Int` w URL.

### Poziom 2 — Średnie (znany wzorzec, ale specyfika projektu)

3. **Screen.kt routing**: Każda trasa z parametrami ma `route` (template)
   i `createUrl()` (konkretny URL). `Country.createUrl(id, name)`,
   `PictureSearchResults.createUrl(categories: Set<Category>)` (serializuje
   do `id1,id2,id3`), `QuickSearchResults.createUrl(query)`.

4. **Kontrakt API crowncaps.info**:
   - `GET data/catalog/caps/countries` → `List<Country>`
   - `GET data/catalog/categories` → `List<Category>`
   - `GET api/v1/countries/{id}/caps?page=&perPage=` → `Page<Cap>`
   - `GET api/v1/caps?query=&page=&perPage=` → `Page<Cap>`
   - `GET api/v1/categories/caps?category[]=&page=&perPage=` → `Page<Cap>`
   - `GET api/v1/caps/latest?page=&perPage=` → `Page<Cap>`
   - `GET api/v1/caps/{id}` → `CapExtended`

5. **`CapExtended` model**: Największy model danych (23 pola). Zawiera
   zagnieżdżone obiekty: `Country`, `Product`, `Purpose`, `Liner`,
   `Producer` (lista), `Series`, `PeriodUsed`, `CapProperty` (lista),
   `SignGroup` (lista), `Category` (lista), `InsideImage` (lista),
   `AdditionalImage` (lista), `UserPublic`.

### Poziom 3 — Proste (standardowe wzorce)

6. **ViewModel + sealed UiState**: Wzorzec `Loading / Success / Error`
   przez `mutableStateOf`. Identyczny dla `CapDetailViewModel`,
   `CountriesViewModel`, `PictureSearchViewModel`.

7. **Hilt DI setup**: `CCIApplication @HiltAndroidApp` + `NetworkModule`
   dostarczający Retrofit + 3 API services + 3 repozytoria jako Singleton.

---

## 4. Rekomendacje dla nowego kontrybutora

1. **Zacznij od `Navigation()` w `MainActivity.kt`** — daje pełny widok
   dostępnych ekranów i ich relacji.
2. **Rozumiej `CapsRepository`** zanim dotkniesz któregokolwiek z 5 VM-ów
   — to wspólny środek ciężkości.
3. **`CapsView.kt` jest współdzielony** przez 4 ekrany — zmiany tu mają
   szeroki zasięg.
4. **Ignoruj `ui/home/search/`** — to porzucony kod, nie odzwierciedla
   produkcyjnego flow.
5. **Sprawdź `CapExtended`** przed zmianami w `CapDetailView` — model
   ma znacznie więcej pól niż renderuje UI.

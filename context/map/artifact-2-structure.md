# Artifact 2 — Structure (Zależności, entry pointy, cykle, lokalne centra)

> Raport roboczy. Analiza statyczna importów i zależności DI.

---

## 1. Entry pointy aplikacji

| Entry point | Typ | Rola |
|---|---|---|
| `CCIApplication` | `@HiltAndroidApp Application` | Bootstrap Hilt DI. Jedyna klasa `Application`. |
| `MainActivity` | `@AndroidEntryPoint ComponentActivity` | Jedyna aktywna Activity (launcher). Montuje `CCITheme` i `Navigation()`. |
| `Navigation()` | `@Composable fun` w `MainActivity.kt` | Korzeń grafu nawigacji. Definiuje 7 tras Compose Navigation. |

`CountriesActivity` zadeklarowana w manifeście **nie istnieje** jako klasa — jest martwą referencją.

---

## 2. Graf zależności (top-down)

```
CCIApplication (@HiltAndroidApp)
  └─ NetworkModule (@Module @InstallIn SingletonComponent)
       ├─ provideBaseURL()          → String "https://crowncaps.info"
       ├─ provideConverterFactory() → Converter.Factory (kotlinx.serialization)
       ├─ provideRetrofitClient()   → Retrofit
       ├─ provideCountriesApiService() → CountryApiService
       ├─ provideCategoriesApiService() → CategoryApiService
       └─ provideCapApiService()    → CapApiService

Repozytoria (wstrzykiwane przez @Inject constructor):
  CapsRepository      ← CapApiService
  CountriesRepository ← CountryApiService
  CategoriesRepository ← CategoryApiService

ViewModels (@HiltViewModel):
  CapDetailViewModel        ← CapsRepository
  QuickSearchViewModel      ← CapsRepository
  CountryCapsViewModel      ← CapsRepository
  LatestCapsViewModel       ← CapsRepository
  PictureSearchCapsViewModel ← CapsRepository
  CountriesViewModel        ← CountriesRepository
  PictureSearchViewModel    ← CategoriesRepository

Screens (Compose) → ViewModels (hiltViewModel()):
  CapDetailScreen           → CapDetailViewModel
  QuickSearchScreen         → QuickSearchViewModel
  CountryCapsScreen         → CountryCapsViewModel
  LatestCapsScreen          → LatestCapsViewModel
  PictureSearchCapsScreen   → PictureSearchCapsViewModel
  CountriesScreen (Countries composable) → CountriesViewModel (przekazany przez Navigation)
  PictureSearchScreen (PictureSearch)    → PictureSearchViewModel
```

---

## 3. Lokalne centra zależności

### Centrum 1: `CapsRepository` — najgorętszy węzeł

Zależy od niego **5 z 7** ViewModeli. Dostarcza 4 PagingSource'ów
(`countryCapsPagingSource`, `latestCapsPagingSource`,
`quickSearchCapsPagingSource`, `pictureSearchCapsPagingSource`) i metodę
`getById` dla szczegółów. Każda zmiana w `CapsRepository` lub
`CapApiService` uderza w połowę ekranów.

### Centrum 2: `ui/catalog/caps/CapsView.kt` — współdzielony widok listy

Używany przez **4 ekrany wynikowe** (QuickSearch, CountryCaps, LatestCaps,
PictureSearchCaps). Enkapsuluje stan paginacji (`LazyPagingItems<Cap>`)
i renderuje siatkę. Zmiana w `CapsView` wpływa na wszystkie cztery ekrany.

### Centrum 3: `Screen.kt` — centralny rejestr nawigacji

Wszystkie 7 tras jest zdefiniowanych w jednym `sealed class Screen`.
Każdy nowy ekran wymaga zmiany tu. Ryzyko konfliktu przy równoległych
zmianach nawigacyjnych.

### Centrum 4: `model/Cap.kt` + `model/CapExtended.kt` — kontrakt danych

`Cap` to model listy (minimalny — 7 pól + `PER_PAGE`). `CapExtended`
to model szczegółów (23 pola z zagnieżdżonymi obiektami). Są serializowane
przez kotlinx.serialization — każda zmiana struktury wymaga zgodności
z API.

---

## 4. Mapa ekranów i nawigacji

```
HomeScreen ("home")
  ├─[→ PictureSearch]  PictureSearchScreen ("picture-search")
  │                          └─[onSearch] PictureSearchCapsScreen ("picture-search?categories={id}")
  │                                              └─[onCapClick] CapDetailScreen ("caps/{capId}")
  ├─[→ Latest]         LatestCapsScreen ("latest")
  │                          └─[onCapClick] CapDetailScreen ("caps/{capId}")
  ├─[→ Countries]      CountriesScreen ("countries")
  │                          └─[onCountryClick] CountryCapsScreen ("countries/{countryId}?name={name}")
  │                                              └─[onCapClick] CapDetailScreen ("caps/{capId}")
  └─[onSearch (TopBar)] QuickSearchScreen ("caps/search?query={query}")
                              └─[onCapClick] CapDetailScreen ("caps/{capId}")
```

`CapDetailScreen` jest końcowym węzłem — osiągany z 4 różnych ścieżek.

---

## 5. Warstwy architektury

```
┌────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)            │
│  screens/ + components/ + theme/       │
├────────────────────────────────────────┤
│  ViewModel Layer (@HiltViewModel)      │
│  7 ViewModeli                          │
├────────────────────────────────────────┤
│  Repository Layer                      │
│  CapsRepository, CountriesRepository,  │
│  CategoriesRepository                  │
│  + 4 PagingSource                      │
├────────────────────────────────────────┤
│  Data Source Layer (Remote)            │
│  CapApiService, CountryApiService,     │
│  CategoryApiService (Retrofit)         │
├────────────────────────────────────────┤
│  DI / Config                           │
│  NetworkModule (Hilt Singleton)        │
│  Base URL: https://crowncaps.info      │
└────────────────────────────────────────┘
```

Brak warstwy cache/offline — każde żądanie trafia bezpośrednio do API.
Brak Room, DataStore ani SharedPreferences.

---

## 6. Cykle zależności

**Brak wykrytych cykli** w grafie DI ani w importach pakietów.

Jedyna niespójność pakietowa: `HomeScreen.kt` i `SearchBar.kt` deklarują
`package pl.sroki.cci.android.ui` zamiast poprawnego
`pl.sroki.cci.android.ui.home` — nie tworzy to cyklu, ale jest
niespójnością konwencji.

---

## 7. Zależności zewnętrzne (build.gradle — kluczowe)

| Biblioteka | Wersja | Rola |
|---|---|---|
| Jetpack Compose (UI) | BOM via `compose_ui_version` | Cały UI |
| Compose Navigation | 2.5.3 | Routing między ekranami |
| Hilt (Dagger) | 2.44 | DI framework |
| Retrofit 2 | 2.9.0 | HTTP klient |
| kotlinx.serialization | 1.4.0 | JSON deserializacja |
| kotlinx.datetime | 0.4.0 | Parsowanie `createdAt: Instant` |
| Paging 3 | 3.1.1 + compose alpha | Paginacja list |
| Coil | 2.2.2 | Ładowanie obrazków |
| Hilt Navigation Compose | 1.0.0 | Integracja Hilt+Navigation |

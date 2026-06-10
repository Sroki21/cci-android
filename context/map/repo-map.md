# Repo Map — cci-android-master

> Finalna synteza. Źródła: artifact-1-territory, artifact-2-structure,
> artifact-3-contributors. Brak git — analiza oparta na statycznej
> zawartości kodu.

---

## Czym jest ten projekt

Aplikacja Android dla serwisu **CrowCaps.Info** — katalogu kapsli
koronkowych. Użytkownik przegląda kolekcję po krajach, kategoriach
wizualnych i najnowszych dodatkach; może też wyszukiwać kapsle tekstowo
i zobaczyć szczegóły każdego kapsla.

- **versionName 1.2** (versionCode 3), minSdk 24, targetSdk 34
- **Backend:** `https://crowncaps.info` (REST + JSON)
- **CDN obrazków:** `https://ddxwnzii69fzh.cloudfront.net`
- **Jeden deweloper** (namespace `pl.sroki`), brak testów, brak cache offline

---

## Mapa ekranów

```
HomeScreen
  ├─ [Picture search] → PictureSearchScreen
  │                          └─ [Search] → PictureSearchCapsScreen → CapDetailScreen
  ├─ [Additions]      → LatestCapsScreen            → CapDetailScreen
  ├─ [Countries]      → CountriesScreen
  │                          └─ [kraj] → CountryCapsScreen         → CapDetailScreen
  └─ [TopBar search]  → QuickSearchScreen            → CapDetailScreen
```

`CapDetailScreen` jest jedynym końcowym węzłem — osiągany z 4 ścieżek.

---

## Architektura (4 warstwy)

```
UI (Compose Screens)
  └─ ViewModel (@HiltViewModel)
       └─ Repository (@Inject)
            └─ ApiService (Retrofit → crowncaps.info)
```

Brak cache, brak Room, brak DataStore. Każde żądanie trafia do sieci.

---

## Lokalne centra — gdzie zmiana boli najbardziej

| Centrum | Zasięg | Pliki |
|---|---|---|
| `CapsRepository` | 5 z 7 VM-ów | `data/CapsRepository.kt` + 4 PagingSource |
| `CapsView.kt` | 4 ekrany wynikowe | `ui/catalog/caps/CapsView.kt` |
| `Screen.kt` | cała nawigacja | `navigation/Screen.kt` |
| `CapExtended` | model szczegółów | `model/CapExtended.kt` (23 pola) |

---

## Entry pointy

| Plik | Rola |
|---|---|
| `CCIApplication.kt` | Bootstrap Hilt (`@HiltAndroidApp`) |
| `MainActivity.kt` | Jedyna Activity (launcher); montuje `Navigation()` |
| `Navigation()` w `MainActivity.kt` | Korzeń grafu — 7 zdefiniowanych tras |
| `di/NetworkModule.kt` | Singleton Retrofit + 3 ApiService'y |

---

## Kontrakt API (crowncaps.info)

| Endpoint | Odpowiedź |
|---|---|
| `GET data/catalog/caps/countries` | `List<Country>` |
| `GET data/catalog/categories` | `List<Category>` |
| `GET api/v1/caps?query=&page=&perPage=` | `Page<Cap>` |
| `GET api/v1/caps/latest?page=&perPage=` | `Page<Cap>` |
| `GET api/v1/caps/{id}` | `CapExtended` |
| `GET api/v1/countries/{id}/caps?page=&perPage=` | `Page<Cap>` |
| `GET api/v1/categories/caps?category[]=&page=&perPage=` | `Page<Cap>` |

Paginacja: `Page<T>` zawiera `data`, `current_page`, `last_page`, `per_page`, `total`.
Rozmiar strony: `Cap.PER_PAGE = 60`.

---

## Znane problemy i dead code

| Problem | Lokalizacja | Priorytet |
|---|---|---|
| `CountriesActivity` w manifeście — klasa nie istnieje | `AndroidManifest.xml:18` | Niski (usuń) |
| Porzucony podsystem wyszukiwania lokalnego | `ui/home/search/`, `model/Search.kt` | Niski (usuń) |
| Literówka w nazwie pliku | `PictureSearchCapsViewModel.kt.kt` | Niski (rename) |
| Dead `Intent` przed właściwym | `CapDetailView.kt:126` | Niski (usuń linię) |
| Błędny pakiet | `HomeScreen.kt`, `SearchBar.kt` deklarują `...ui` zamiast `...ui.home` | Niski (refactor) |
| Brak retry na błąd API | Wszystkie ekrany obsługują błąd przez `Text("Error")` | Średni |
| VM z mutowalnym `var id`/`var query` | `CountryCapsViewModel`, `QuickSearchViewModel`, `PictureSearchCapsViewModel` | Średni |

---

## Niezakończone funkcjonalności (TODO w kodzie)

Wszystkie trzy dotyczą `CapDetailView.kt` — model API je zwraca, UI nie renderuje:

- `insideImages` — zdjęcia wewnętrzne kapsla
- `images` (AdditionalImage) — dodatkowe zdjęcia
- `usersCount` / lista użytkowników posiadających kapsel

---

## Dla nowego kontrybutora — kolejność eksploracji

1. `MainActivity.kt` — pełny widok ekranów i tras
2. `navigation/Screen.kt` — definicje URL
3. `data/CapsRepository.kt` — wspólny hub danych
4. `ui/catalog/caps/CapsView.kt` — współdzielony widok listy
5. `ui/catalog/caps/detail/CapDetailView.kt` — najbardziej rozbudowany ekran
6. **Ignoruj** `ui/home/search/` — porzucony kod, nie jest podpięty do nawigacji

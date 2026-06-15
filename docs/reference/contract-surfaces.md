# Contract Surfaces — Load-Bearing Names Registry

> Nazwy, których zmiana łamie inne warstwy lub zewnętrzny kontrakt (API, Firestore, Room).
> Przed zmianą nazwy na tej liście — sprawdź ALL callers. Po zmianie — zaktualizuj ten plik.

## API — URL paths (Retrofit `@GET`/`@POST`/`@DELETE`)

Zmiana ścieżki URL = zmiana kontraktu z serwerem crowncaps.info. Nie zmieniać bez
weryfikacji działania na żywym API.

| Endpoint | Metoda | Plik |
|---|---|---|
| `api/v1/caps/latest` | `CapApiService.getLatest()` | `data/datasource/remote/CapApiService.kt:29` |
| `api/v1/caps/{id}` | `CapApiService.getById()` | `data/datasource/remote/CapApiService.kt:34` |
| `api/v1/caps` (query) | `CapApiService.getByQuery()` | `data/datasource/remote/CapApiService.kt:37` |
| `api/v1/caps` (advanced) | `CapApiService.advancedSearch()` | `data/datasource/remote/CapApiService.kt:51` |
| `api/v1/countries/{countryId}/caps` | `CapApiService.getByCountryId()` | `data/datasource/remote/CapApiService.kt:21` |
| `api/v1/categories/caps` | `CapApiService.getByCategoryIds()` | `data/datasource/remote/CapApiService.kt:44` |
| `data/catalog/caps/countries` | `CountryApiService.getCountries()` | `data/datasource/remote/CountryApiService.kt` |
| `data/catalog/categories` | `CategoryApiService.getCategories()` | `data/datasource/remote/CategoryApiService.kt` |
| `data/catalog/caps/similar` | `CapApiService.searchSimilar()` | `data/datasource/remote/CapApiService.kt:61` |
| `data/catalog/caps/search` | `CapApiService.searchCapsByFilter()` | `data/datasource/remote/CapApiService.kt:68` |
| `data/catalog/caps/{id}/collection` | `CapApiService.addToCollection()` / `removeFromCollection()` | `data/datasource/remote/CapApiService.kt:74-78` |
| `sanctum/csrf-cookie` | `AuthApiService` | `data/datasource/remote/auth/AuthApiService.kt` |
| `auth/login` | `AuthApiService` | `data/datasource/remote/auth/AuthApiService.kt` |
| `data/users/current` | `AuthApiService` | `data/datasource/remote/auth/AuthApiService.kt` |
| `logout` | `AuthApiService` | `data/datasource/remote/auth/AuthApiService.kt` |

## API — pola JSON (modele z `@SerialName`)

Pola JSON korzystające z `@SerialName` — zmiana nazwy w Kotlin wymaga aktualizacji adnotacji.

| Model | Pole Kotlin | Klucz JSON | Plik |
|---|---|---|---|
| `Page<T>` | `currentPage` | `current_page` | `model/Page.kt` |
| `Page<T>` | `lastPage` | `last_page` | `model/Page.kt` |
| `Page<T>` | `perPage` | `per_page` | `model/Page.kt` |
| `Cap` | `isInCollection` | `isInCollection` (camelCase — brak `@SerialName`) | `model/Cap.kt` |
| `Cap` | `imageUrl` | `imageUrl` (camelCase — brak `@SerialName`) | `model/Cap.kt` |

## Nawigacja — trasy (`Screen.kt`)

Zmiana `route` łamie back stack i `NavHost` w `MainActivity.kt`.
Trasy z parametrami mają też `createUrl()` — zmieniać oba jednocześnie.

| Obiekt | route | createUrl() |
|---|---|---|
| `Screen.Home` | `"home"` | — |
| `Screen.Countries` | `"countries"` | — |
| `Screen.Country` | `"countries/{countryId}?name={name}"` | `createUrl(id, name)` |
| `Screen.Latest` | `"latest"` | — |
| `Screen.CapDetail` | `"caps/{capId}"` | `createUrl(id)` |
| `Screen.QuickSearchResults` | `"caps/search?query={query}"` | `createUrl(query)` |
| `Screen.PictureSearch` | `"picture-search"` | — |
| `Screen.PictureSearchResults` | `"picture-search?categories={id}"` | `createUrl(categories)` |
| `Screen.AdvancedSearch` | `"advanced-search"` | — |
| `Screen.Purchased` | `"purchased"` | — |
| `Screen.Login` | `"login"` | — |
| `Screen.Binders` | `"binders"` | — |
| `Screen.Statistics` | `"statistics"` | — |
| `Screen.OwnedCountries` | `"owned-countries"` | — |
| `Screen.CountryOwnedCaps` | `"owned-caps?country={country}"` | `createUrl(country)` |
| `Screen.LocationsMap` | `"locations-map"` | — |
| `Screen.CollectionVerification` | `"collection-verification"` | — |

## Room — tabele i kolumny

Zmiana `tableName` lub `ColumnInfo(name)` wymaga migracji Room (nowa wersja DB).

| Encja | tableName | Kluczowe kolumny | Plik |
|---|---|---|---|
| `Binder` | `"binder"` | `id`, `name`, `firestore_id` | `data/datasource/local/entity/Binder.kt` |
| `BinderPage` | `"binder_page"` | `id`, `binder_id`, `number`, `firestore_id` | `data/datasource/local/entity/BinderPage.kt` |
| `CapPosition` | `"cap_position"` | `id`, `binder_page_id`, `position`, `cap_id`, `firestore_id` | `data/datasource/local/entity/CapPosition.kt` |
| `PendingCap` | `"pending_cap"` | `cap_id` | `data/datasource/local/entity/PendingCap.kt` |

**Unique constraint:** `(binder_page_id, position)` w `cap_position` — egzekwowane przez Room index.

## Firestore — kolekcje i pola dokumentów

Zmiana nazwy kolekcji lub pola = dane istniejących użytkowników stają się niewidoczne
dla nowej wersji aplikacji (Firestore nie migruje automatycznie).

| Kolekcja | Dokument | Kluczowe pola | Serwis |
|---|---|---|---|
| `users/{uid}/binders` | `BinderDocument` | `name`, `createdAt` | `BinderFirestoreService.kt` |
| `users/{uid}/binder_pages` | `BinderPageDocument` | `binderId`, `number` | `BinderPageFirestoreService.kt` |
| `users/{uid}/cap_positions` | `CapPositionDocument` | `binderPageId`, `position`, `capId` | (Firestore service per zmiana) |

## `collectionChanged` SharedFlow

`CapsRepository.collectionChanged: SharedFlow<Unit>` — emitowany po `addToCollection()`
i `removeFromCollection()`. Subskrybują go wszystkie ViewModele invalidujące paginację
(`LatestCapsViewModel`, `QuickSearchViewModel`, `AdvancedSearchViewModel`, …).
Dodając nowy ViewModel z listą kapsli — **musisz** subskrybować ten flow.

## `Cap.PER_PAGE`

Stała określająca `pageSize` dla wszystkich PagingSource opartych o `CapsRepository`.
API `/api/v1/caps?query=` ignoruje `perPage` i zawsze zwraca 20 — `PER_PAGE = 20`
musi być zgodne z tym zachowaniem. Zmiana wymaga weryfikacji wszystkich endpointów.

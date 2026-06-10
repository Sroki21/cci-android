# Artifact 1 — Territory (Historia zmian i aktywne obszary)

> Raport roboczy. Brak repozytorium git — analiza opiera się wyłącznie na
> statycznej zawartości kodu źródłowego i znacznikach w nim zawartych.

---

## 1. Wersja i metadane projektu

| Pole | Wartość |
|---|---|
| `applicationId` | `pl.sroki.cci.android` |
| `versionCode` | 3 |
| `versionName` | 1.2 |
| `minSdk` | 24 (Android 7.0) |
| `targetSdk` / `compileSdk` | 34 (Android 14) |
| `namespace` | `pl.sroki.cci` |
| Backend API | `https://crowncaps.info` |
| CDN obrazków | `https://ddxwnzii69fzh.cloudfront.net` |

Projekt jest aplikacją mobilną dla serwisu CrowCaps.Info — katalogu
kapsli koronkowych. Użytkownik może przeglądać kolekcję po krajach,
kategoriach wizualnych (picture search) i najnowszych dodatkach, a
także wyszukiwać kapsle tekstowo.

---

## 2. Ślady ewolucji widoczne w kodzie

Brak git — nie można odtworzyć historii commitów. Wnioski poniżej
opierają się na anomaliach i komentarzach wewnątrz kodu.

### 2.1 Martwy kod / legacy artefakty

| Plik | Obserwacja |
|---|---|
| `AndroidManifest.xml:18` | Zadeklarowana aktywność `CountriesActivity` nie istnieje jako plik `.kt`. Relikt wcześniejszej, tradycyjnej (nie-Compose) architektury. |
| `model/Search.kt` + `ui/home/search/Search.kt` + `ui/home/search/Results.kt` | Kompletny, lokalny mechanizm wyszukiwania oparty na statycznych danych (`SearchRepo` z fake-listą `caps`). Nigdy nie jest wywoływany z `MainActivity` — jest izolowanym, porzuconym eksperymentem. |
| `model/Cap.kt` — statyczna lista `val caps` | Hardcoded dane testowe (2 kapsle Heineken). Używane tylko w Preview i `SearchRepo`. |
| `data/Countries.kt` — `fakeCountries` | Statyczne dane testowe używane wyłącznie w Preview. |

### 2.2 Komentarze TODO — niezakończone funkcjonalności

| Lokalizacja | Treść TODO |
|---|---|
| `ui/catalog/caps/detail/CapDetailView.kt:82` | `// TODO additionalImages` |
| `ui/catalog/caps/detail/CapDetailView.kt:83` | `// TODO inside images` |
| `ui/catalog/caps/detail/CapDetailView.kt:105` | `// TODO who has this one?` |

Model `CapExtended` ma już pola `insideImages`, `images` i `usersCount`
— API je zwraca, ale UI ich jeszcze nie renderuje.

### 2.3 Usterki / anomalie techniczne

| Plik | Obserwacja |
|---|---|
| `ui/catalog/picturesearch/PictureSearchCapsViewModel.kt.kt` | Podwójne rozszerzenie `.kt.kt` w nazwie pliku — ewidentna literówka. |
| `ui/catalog/caps/detail/CapDetailView.kt:126` | `openProducerUrl` tworzy martwy `Intent` z `google.com` (linia 126), po czym natychmiast go nadpisuje właściwym URL (linia 127). Dead code — pierwsza linia jest bezcelowa. |
| `QuickSearchViewModel.kt` | `query` i `id` w `QuickSearchViewModel` / `CountryCapsViewModel` / `PictureSearchCapsViewModel` są ustawiane po stworzeniu VM przez przypisanie do `var` przed pierwszym zebraniem flow. Jest to wzorzec ryzykowny (race condition przy odtworzeniu VM). |
| `model/Search.kt` — `SearchRepo` | Używa `delay(200)` do symulacji opóźnienia — nigdy nie będzie użyte produkcyjnie. |

### 2.4 Anomalia package vs. katalog

`HomeScreen.kt` i `SearchBar.kt` żyją w katalogu `ui/home/`, ale ich
deklaracja `package` wskazuje `pl.sroki.cci.android.ui` (bez `.home`).
Są importowane z tego błędnego pakietu w `MainActivity`. To niespójność
między strukturą katalogów a pakietami Kotlina.

---

## 3. Aktywne obszary kodu

### WYSOKA aktywność / złożoność

- **`ui/catalog/caps/detail/`** — największy ekran (8 plików), jedyny z
  sekcjami (Series, Signs, Producers), z TODO i z logiką Intent.
- **`data/`** — 4 `PagingSource` + 3 repozytoria + 3 API service'y —
  centrum przepływu danych.

### ŚREDNIA aktywność

- **`ui/catalog/picturesearch/`** — dwa oddzielne ekrany (selektor
  kategorii + wyniki) z dwoma VM-ami. Jedyny flow multi-krokowy (wybierz
  kategorie → szukaj).
- **`navigation/Screen.kt`** — centralny rejestr tras; 7 ekranów zdefiniowanych.

### NISKA aktywność / stabilne

- **`ui/catalog/countries/`**, **`ui/catalog/country/`**, **`ui/catalog/latest/`**
  — wzorcowe, powtarzalne ekrany lista→siatka.
- **`di/NetworkModule.kt`** — jednorazowa konfiguracja DI.
- **`ui/theme/`** — paleta kolorów, typografia, kształty. Stabilne.
- **`ui/components/FullSizeLoader.kt`** — współdzielony komponent loading.

### MARTWE / porzucone

- `ui/home/search/` (Search.kt, Results.kt)
- `model/Search.kt` (SearchRepo)
- `model/Cap.kt` — lista statyczna `caps`
- `data/Countries.kt` — `fakeCountries` (tylko preview)

---

## 4. Podsumowanie stanu projektu

Projekt jest w stanie **wczesnej produkcji / finalizacji MVP**:
- Szczęśliwa ścieżka działa: przeglądanie krajów, najnowszych kapsli,
  wyszukiwanie tekstowe, szczegóły kapsla.
- Trzy funkcje w `CapDetailView` są zaślepione przez TODO (zdjęcia
  dodatkowe, zdjęcia wewnętrzne, lista użytkowników posiadających kapsel).
- Jeden relikt starszej architektury (`CountriesActivity`) czeka na
  usunięcie z manifestu.
- Jeden izolowany podsystem (lokalne wyszukiwanie z `SearchRepo`) nigdy
  nie trafił do nawigacji — do usunięcia lub przebudowania.

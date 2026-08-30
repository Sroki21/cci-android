---
title: "CCI Android — Destylacja domeny"
created: 2026-06-16
type: domain-distillation
sources:
  - context/foundation/prd.md (v1)
  - context/foundation/shape-notes.md (accepted)
  - context/foundation/roadmap.md (v6)
  - app/src/main/java/pl/sroki/cci/android/ (kod źródłowy)
---

# CCI Android — Destylacja domeny biznesowej

## KROK 0 — Kontekst projektu

### Dokumenty bazowe

Projekt posiada pełen zestaw dokumentów wymagań:
- **PRD v1** (`context/foundation/prd.md`) — główne źródło wymagań funkcjonalnych i reguł biznesowych.
- **Shape notes** (`context/foundation/shape-notes.md`) — decyzje projektowe (zaakceptowane).
- **Roadmap v6** (`context/foundation/roadmap.md`) — wszystkie roadmap items ze statusem `done`.

### Stack i struktura repo

```
app/src/main/java/pl/sroki/cci/android/
  model/                      ← Modele domenowe: Cap, CapExtended, Producer, Series…
  data/
    datasource/remote/        ← Interfejsy Retrofit (CapApiService, Auth, Country, Category…)
    datasource/remote/firestore/ ← Firestore services (Binder, BinderPage, CapPosition)
    datasource/local/entity/  ← Encje Room: Binder, BinderPage, CapPosition, PendingCap, CapCache
    datasource/local/dao/     ← DAOs
    *Repository.kt            ← Repozytoria per domena
    *UseCase.kt               ← Jeden use case: FirestoreRestoreUseCase
  ui/                         ← ViewModels + Composable screens
  di/                         ← Moduły Hilt
```

Logika biznesowa koncentruje się w warstwie `data/*Repository.kt` oraz — co istotne —
częściowo wycieka do `ui/.../CapDetailViewModel.kt` (500 linii, zawiera algorytm sugestii klasera).

---

## KROK 1 — Ubiquitous Language

| # | Pojęcie | Definicja | Cytat źródłowy | Kod (plik:linia) |
|---|---------|-----------|----------------|------------------|
| 1 | **Cap (Kapsel)** | Metalowa kapsla od butelki piwa; podstawowy obiekt kolekcjonerski, identyfikowany unikalnym ID w bazie crowncaps.info. | `prd.md:24` "ponad 366 000 kapsli, 229 krajów" | `model/Cap.kt:8` |
| 2 | **isInCollection** | Pole binarne (true/false) zwracane przez API crowncaps.info dla zalogowanego użytkownika. Autorytatywne źródło prawdy o posiadaniu kapsla. | `prd.md:34` "API crowncaps.info już zwraca `isInCollection` per kapsel dla zalogowanego użytkownika" | `model/Cap.kt:16`, `model/CapExtended.kt:33` |
| 3 | **Stan posiadania (Ownership Status)** | Trójstanowa klasyfikacja kapsla: (1) `MISSING` — nie posiadam (isInCollection=false), (2) `PURCHASED` — posiadam, nieskatalogowany, (3) `IN_COLLECTION` — posiadam + przypisany do pozycji w klaserze. | `prd.md:132` "Wyjście reguły to jeden z trzech stanów: 'nie posiadam'… 'posiadam, nieskatalogowany'… 'posiadam, skatalogowany'" | `ui/catalog/caps/detail/CapDetailViewModel.kt:32` (enum `CapStatus`) — **istnieje wyłącznie lokalnie w CapDetailViewModel; brak jako współdzielony model domenowy** |
| 4 | **Klaser (Binder)** | Fizyczny album kolekcjonera, nazwany kontynentem + numerem sekwencyjnym (np. "Polska 2"). Zawiera 1–15 stron. | `prd.md:28` "klaser (kontynent + numer)" | `data/datasource/local/entity/Binder.kt:8` |
| 5 | **Strona (BinderPage)** | Fizyczna strona klasera, numerowana od 1. Limit 15 stron na klaser. | `prd.md:105` "FR-011: limit 1–15 na klaser" | `data/datasource/local/entity/BinderPage.kt:24` (UNIQUE INDEX `binder_id, page_number`) |
| 6 | **Pozycja / Slot (CapPosition)** | Unikalna trójka (klaser, strona, pozycja 1–35) identyfikująca jedno miejsce fizyczne. Każda strona ma dokładnie 35 pozycji. | `prd.md:55` "Slot (klaser, strona, pozycja) jest zawsze unikalny" | `data/datasource/local/entity/CapPosition.kt:19-21` (UNIQUE INDEX `binder_page_id, position`) |
| 7 | **Kapsel oczekujący (PendingCap)** | Kapsel oznaczony jako "kupiony", czekający na przypisanie do pozycji w klaserze. | `prd.md:48` "lokalnie kapsel trafia do zakładki 'oczekuje na skatalogowanie'" | `data/datasource/local/entity/PendingCap.kt:7` — **Room entity istnieje, ale żaden ViewModel jej nie używa (martwy kod)** |
| 8 | **PurchasedCapsLocalStore** | SharedPreferences przechowujące IDs kapsli "kupionych" — faktyczny mechanizm zakładki oczekujących używany przez UI. | BRAK w PRD | `data/PurchasedCapsLocalStore.kt:9` — **replaces PendingCap w praktyce** |
| 9 | **Zakup / Oznaczenie kupionego (Mark as Bought)** | Akcja jednym tapnięciem: wywołanie `POST data/catalog/caps/{id}/collection` na API + lokalne zapisanie ID w PurchasedCapsLocalStore. | `prd.md:92` "FR-007: jednym tapnięciem oznacza kapsel jako 'kupiony'" | `data/CapsRepository.kt:90-96` |
| 10 | **Katalogowanie (Cataloging)** | Przypisanie kapsla z listy oczekujących do konkretnej pozycji (klaser, strona 1–15, pozycja 1–35). Kapsel znika z listy oczekujących po przypisaniu. | `prd.md:49` "przypisuje kapsel do (klaser, strona, pozycja)" | `data/CapPositionRepository.kt:31-39` (`assign`) |
| 11 | **Snapshot kapsla (CapSnapshot)** | Niezmienny odcisk danych kapsla z API (name, country, imageUrl, createdAt, createdById, updatedAt) przechwytywany przy katalogowaniu. Umożliwia render offline i wykrycie rozbieżności z katalogiem crowncaps. | BRAK w PRD (implemetacyjna decyzja resilience) | `data/model/CapSnapshot.kt:8`, `data/datasource/local/entity/CapCache.kt:7` |
| 12 | **Cache kapsla (CapCache)** | Lokalny rekord: snapshot + status weryfikacji. Umożliwia wyświetlanie kolekcji offline po reinstalacji i sygnalizuje "rozjazd" z katalogiem. | BRAK w PRD | `data/datasource/local/entity/CapCache.kt:7` (`catalogStatus`, `lastVerifiedAt`) |
| 13 | **Rozjazd (Catalog Drift)** | Stan, w którym lokalny snapshot kapsla różni się od aktualnych danych z crowncaps.info. Sygnalizowany przez `catalogStatus` ≠ "ok". Użytkownik decyduje: zachowaj snapshot / zaakceptuj nowe / odepnij. | BRAK w PRD | `ui/catalog/caps/detail/CapDetailViewModel.kt:77-78` (`catalogStatus`), `data/datasource/local/entity/CapCache.kt:19` |
| 14 | **Sugestia klasera (BinderSuggestion)** | Automatyczna podpowiedź systemu: do którego klasera/strony/pozycji powinien trafić nowy kapsel, bazując na kraju i dotychczasowym wypełnieniu. | BRAK w PRD | `model/BinderSuggestion.kt`, `ui/catalog/caps/detail/CapDetailViewModel.kt:252-373` |
| 15 | **Zapełnienie klaserów (Binder Fill Stats)** | Obliczenie: ile wolnych pozycji (maks. 35 − zajęte) na każdej stronie każdego klasera. Obliczane lokalnie z Room. | `prd.md:104` "FR-013: Użytkownik widzi zapełnienie klaserów" | `data/datasource/local/dao/CapPositionDao.kt:52-57` |
| 16 | **Autentykacja / Sesja (Auth Session)** | Stan zalogowania przez konto crowncaps.info. Mechanizm: Laravel Sanctum (cookie session + Bearer API token). Persystowany w PersistentCookieJar + SharedPreferences. | `prd.md:88` "FR-001: Użytkownik może zalogować się kontem crowncaps.info" | `data/AuthRepository.kt`, `data/SessionRepository.kt` |
| 17 | **Firestore Sync** | Dual-write do Firebase Firestore jako backup struktury klaserów/stron/pozycji. Room jest primary (offline-first); Firestore umożliwia odtworzenie danych po reinstalacji. | `roadmap.md:114-125` "F-03" — **wykracza poza PRD NFR** | `data/datasource/remote/firestore/`, `data/FirestoreRestoreUseCase.kt` |

---

## KROK 2 — Klasyfikacja subdomen

| Subdomena | Kategoria | Uzasadnienie |
|-----------|-----------|--------------|
| **Zarządzanie strukturą klaserów** (Binder → BinderPage → CapPosition) | **Core** | To jest unikalna propozycja wartości produktu: „zastąpienie Excela". PRD `Success Criteria Primary #2`. Żadna inna aplikacja ani crowncaps.info nie oferuje tej hierarchii. |
| **Status posiadania (isInCollection) + zakup** | **Core** | Drugie ramię North Star. PRD `Success Criteria Primary #1`. Bez odpowiedzi „czy mam?" całkowita wartość produktu odpada. |
| **Katalogowanie (assign/reassign/unassign)** | **Core** | Operacja spinająca oba Core: łączy isInCollection z pozycją w klaserze. Egzekwuje główny niezmiennik domenowy. |
| **Przeglądanie bazy crowncaps.info** (search, browse, latest) | **Supporting** | Niezbędna infrastruktura do identyfikacji kapsla, ale crowncaps.info oferuje to niezależnie od tej aplikacji. PRD `FR-002, FR-004–FR-006 preserved`. |
| **Autentykacja** (Sanctum session + API token) | **Supporting** | Brama wejścia do Core, ale jest mechanizmem dostępu, nie logiką biznesową. Wymienialny bez zmiany reguł domeny. |
| **Rozjazd kapsla (CapCache + catalogStatus)** | **Supporting** | Ważna dla spójności danych, ale nie wymieniana w PRD jako cel produktu. Zwiększa niezawodność Core. |
| **Firestore Sync** | **Generic** | Backup/resilience pattern. Nie definiuje wartości produktu; mógłby być zastąpiony innym mechanizmem backupu bez zmiany domeny. |
| **CapCache / CapSnapshot** | **Generic** | Wzorzec offline-first snapshot; techniczne rozwiązanie infrastrukturalne. |
| **Paginacja, UI Theme, Navigation** | **Generic** | Powszechne wzorce frameworkowe; brak wartości domenowej. |

---

## KROK 3 — Kandydaci na agregaty i ich niezmienniki

### Agregat A: Binder (Klaser)

**Granica:** Binder → BinderPage[] (1–15) → CapPosition[] (1–35 per page)

| Niezmiennik | Reguła biznesowa | Egzekwowanie w kodzie | Status |
|-------------|------------------|-----------------------|--------|
| **N-A1:** Klaser ma maks. 15 stron | `prd.md:105` "FR-011: próba dodania 16. strony kończy się czytelnym błędem" | `data/BinderPageRepository.kt:32` `check(count < 15)` | ✅ Egzekwowany (Runtime check w Repository) |
| **N-A2:** Strona ma maks. 35 pozycji (numery 1–35) | `prd.md:105` "każda strona ma zawsze 35 pozycji" | `data/CapPositionRepository.kt:32` `require(position in 1..35)` | ✅ Egzekwowany (Runtime check) |
| **N-A3:** Slot (strona, pozycja) unikalny — żadne dwa kapsle w tym samym miejscu | `prd.md:55` "Slot jest zawsze unikalny — aplikacja blokuje przypisanie dwóch kapsli do tej samej pozycji" | `data/datasource/local/entity/CapPosition.kt:19-21` UNIQUE INDEX; `CapPositionDao:22` `OnConflictStrategy.IGNORE` | ⚠️ Deklarowany (DB constraint), ale `IGNORE` cicho połyka duplikaty zamiast zgłaszać błąd — niezmiennik jest łamany bez informowania użytkownika przy restore |
| **N-A4:** Klaser z kapslami nie może być usunięty | `prd.md:99` "usuwanie tylko pustego klasera — aplikacja blokuje usunięcie" | `data/BinderRepository.kt:33-34` `check(occupied == 0)` | ✅ Egzekwowany (Runtime check + FK CASCADE jako fallback) |
| **N-A5:** Kapsel jest przypisany do co najwyżej jednej pozycji | Wynika z reguły unikalności slotu | `CapPositionDao:35` `getByCapId LIMIT 1`; `reassignFull` usuwa starą przed insertą | ✅ Egzekwowany (transakcja `reassignFull`) |

**Problem granicy agregatu:** Binder, BinderPage i CapPosition żyją w trzech oddzielnych repozytoriach (`BinderRepository`, `BinderPageRepository`, `CapPositionRepository`). Nie ma Aggregate Root koordynującego. Transakcyjność między repozytoriami jest zapewniona tylko przy `addPage` (przez `db.withTransaction`), ale nie przy operacjach między BinderRepository a CapPositionRepository.

---

### Agregat B: CollectionEntry (Wpis kolekcji) — kandydat nieistniejący w kodzie

**Pojęcie domenowe:** "co kolekcjoner kupił i gdzie to jest" — łączy `isInCollection` (API), `PurchasedCapsLocalStore` (local), `PendingCap` (Room dead), `CapPosition` (Room).

| Niezmiennik | Reguła biznesowa | Egzekwowanie w kodzie | Status |
|-------------|------------------|-----------------------|--------|
| **N-B1:** Kapsel jest albo "pending" albo "assigned" — nigdy oba | Wynika z `prd.md:49`: po katalogowaniu kapsel "znika z listy oczekujących" | `ui/catalog/purchased/PurchasedViewModel.kt:49` `purchasedIds - assignedIds` — obliczenie w UI, brak transakcji | ❌ Niegrewowany transakcyjnie — obliczenie w ViewModel, nie w domenie |
| **N-B2:** Oznaczenie "kupuję" musi się powieść w API, żeby stać się lokalnym stanem | `prd.md:71` "Jeżeli rejestracja nie powiedzie się, kapsel NIE jest oznaczony" | `data/CapsRepository.kt:90-96` — throw jeśli HTTP nieoK, potem `purchasedCapsLocalStore.add()` | ✅ Egzekwowany przez kolejność operacji w CapsRepository |
| **N-B3:** Stan posiadania jest trójwartościowy (MISSING/PURCHASED/IN_COLLECTION) | `prd.md:132` trzy stany explicite | `ui/.../CapDetailViewModel.kt:32` enum `CapStatus` — **lokalny enum, brak w warstwach Repository/model** | ❌ Brak domenowego modelu; stan inferowany ad-hoc przez każdy ViewModel niezależnie |

---

## KROK 4 — Rozjazdy MODEL vs KOD

| # | Dokument mówi | Kod robi | Dowód (plik:linia) |
|---|---------------|----------|---------------------|
| **R-1** | PRD definiuje trzy stany posiadania: `nie posiadam` / `posiadam nieskatalogowany` / `posiadam skatalogowany` | Enum `CapStatus` istnieje tylko w `CapDetailViewModel` (1 ViewModel). Inne ViewModely (`PurchasedViewModel`, `CapsView`, `CapGridView`) używają osobnych flag lub ignorują trzeci stan. Brak shared domain model. | `CapDetailViewModel.kt:32`, `PurchasedViewModel.kt:48-50` — obliczenie `purchasedIds - assignedIds` bez CapStatus |
| **R-2** | PRD: kapsel "trafia do zakładki oczekuje na skatalogowanie" po tapnięciu "kupuję" — sugeruje lokalne przechowywanie (Room `pending_cap`) | Room entity `PendingCap` i `PendingCapRepository` istnieją i są poprawnie zbudowane, lecz **żaden ViewModel ich nie wstrzykuje ani nie wywołuje**. Faktyczna zakładka oczekujących opiera się na `PurchasedCapsLocalStore` (SharedPreferences). | `PendingCapRepository.kt:11` — tylko jeden `@Inject constructor`, zero odwołań w UI; `PurchasedViewModel.kt:28` używa `PurchasedCapsLocalStore` |
| **R-3** | PRD Non-Goals: "Dane struktury klaserów są przechowywane **wyłącznie na urządzeniu** i nie opuszczają go" | F-03 (Firestore Sync) replikuje dane klaserów/stron/pozycji do Firebase Firestore (chmura). | `roadmap.md:124` "Uwaga: ta foundation wykracza poza PRD NFR"; `data/datasource/remote/firestore/BinderFirestoreService.kt:14-17` |
| **R-4** | PRD Guardrail: "Slot jest zawsze unikalny — aplikacja **blokuje** przypisanie" | `CapPositionDao.insertOrIgnore` używa `OnConflictStrategy.IGNORE` — duplikat jest cicho odrzucany (zwraca -1) bez wyjątku i bez informowania użytkownika. Egzekwowanie istnieje, ale bez widocznego feedbacku. | `data/datasource/local/dao/CapPositionDao.kt:22-23` |
| **R-5** | PRD: "Edycja przypisania musi być możliwa — stara pozycja zwalnia się automatycznie" | `CapPositionRepository.reassign/reassignFull` istnieje i zwalnia. Jednak UI `CapDetailViewModel.saveAssignment()` dla stanu `IN_COLLECTION` wywołuje `reassign`, ale nie aktualizuje `PurchasedCapsLocalStore` — po reassign kapsel nadal istnieje w `PurchasedCapsLocalStore` bezpotrzebnie (leakage). | `CapDetailViewModel.kt:162-164` vs `PurchasedCapsLocalStore` — brak `remove()` przy przejściu do `IN_COLLECTION` |
| **R-6** | PRD: reguła uzupełniająca "klaser ma od 1 do 15 stron" — granica **dolna** 1 strona | Brak reguły blokującej `deletePage` gdy strona jest jedyna (ostatnia). Klaser może zostać z 0 stronami. | `data/BinderPageRepository.kt:43-54` `deletePage` — brak `check(count > 1)` |
| **R-7** | PRD Success Criteria Secondary: "zapełnienie klaserów — ile wolnych pozycji na każdej **stronie** każdego klasera" | Obliczenie w `CapPositionDao.countByBinderId` zwraca sumę per klaser, nie per strona. Widok Binder Fill Stats musi łączyć dane per-strona z osobnego query. | `data/datasource/local/dao/CapPositionDao.kt:52-57` vs PRD wymaganie per-strona |
| **R-8** | Brak w PRD — `CapCache`, `CapSnapshot`, `catalogStatus`, "rozjazd" to implementacyjne pojęcia | Implementacja wprowadza bogaty model rozjazdu (keep/accept/unlink z pełnym UI) niewidoczny w PRD. Stanowi ukrytą poddomenę Supporting. | `data/datasource/local/entity/CapCache.kt:7-21`, `CapDetailViewModel.kt:113-145` |

---

## KROK 5 — Ranking refaktoru

### Macierz wartość / ryzyko

| Kandydat | Wartość (jak Core niezmiennik?) | Ryzyko niefunkcji (jak słabo egzekwowany?) | Priorytet |
|----------|---------------------------------|--------------------------------------------|-----------|
| **[#1] Trójstanowy model posiadania (CollectionEntry)** | ⭐⭐⭐⭐⭐ — to rdzeń Core: odpowiedź na "czy mam?" i "gdzie jest?" | Wysoki — stan inferowany ad-hoc w 3+ miejscach; `PendingCap` martwy; brak transakcji między standalone stores | **#1** |
| **[#2] Martwy PendingCap vs aktywny PurchasedCapsLocalStore** | ⭐⭐⭐⭐ — dublowanie: Room entity ignorowane, SharedPreferences w użyciu | Średni — brak błędów produkcyjnych, ale dług techniczny: Room entity marnuje schematyczną przestrzeń i intro­dukuje confusję | **#2** |
| **[#3] Brak Aggregate Root dla Binder** | ⭐⭐⭐⭐ — klaser jest głównym obiektem Core; granice agregatu rozmyte przez 3 oddzielne Repozytoria | Średni — niezmienniki są egzekwowane, ale bez transakcji między BinderRepository a CapPositionRepository | **#3** |
| **[#4] BinderSuggestion w ViewModel (500 linii)** | ⭐⭐⭐ — algorytm sugestii jest wartościowym UX, nie należy do warstwy UI | Niski — działa poprawnie; problem to utrzymywalność | **#4** |
| **[#5] OnConflictStrategy.IGNORE w CapPosition bez feedbacku** | ⭐⭐⭐ — Guardrail "blokuje przypisanie" z PRD nie ma widocznego komunikatu | Niski — duplikat jest odrzucany (dane bezpieczne), ale UX jest cichy | **#5** |
| **[#6] Brak dolnej granicy stron klasera (może mieć 0 stron)** | ⭐⭐ — PRD mówi "od 1 do 15 stron" | Niski — sytuacja brzegowa; klaser bez stron jest bezużyteczny, ale nie niszczy danych | **#6** |

---

### #1 do refaktoru: Trójstanowy model posiadania

**Dlaczego #1:**
PRD explicite definiuje trzy stany (`prd.md:132`) jako "wyjście reguły domenowej". Obecny kod:
- rozpraszą ten stan na 4 niezależne struktury danych (`isInCollection` z API, `PurchasedCapsLocalStore` SharedPreferences, `PendingCap` Room [martwy], `CapPosition` Room);
- re-implementuje każdy ViewModel tę samą logikę inferowania stanu od nowa;
- enum `CapStatus` (`IN_COLLECTION, PURCHASED, MISSING`) istnieje tylko lokalnie w `CapDetailViewModel.kt:32` — jest właściwą abstrakcją, ale w złym miejscu.

**Sugestia kierunku:**
Przenieść `CapStatus` do warstwy domenowej (`model/`), zamknąć logikę inferowania w jednym dedykowanym repozytorium/use-case `CollectionStatusRepository` (lub rozszerzyć `CapsRepository`), i zastąpić `PurchasedCapsLocalStore` (SharedPreferences) poprawnie podłączonym `PendingCapRepository` (Room) — Room oferuje Flow, transakcje i migracje, czego SharedPreferences nie ma.

---

*Artefakt zbadany: prd.md, shape-notes.md, roadmap.md, 35+ plików .kt.*
*Ograniczenie: archiwizowane plans i impl-reviews nie były potrzebne do destylacji — bazowano wyłącznie na aktywnym kodzie i dokumentach foundation.*

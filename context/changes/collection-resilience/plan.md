# Odporność kolekcji na zmiany w katalogu crowncaps — Implementation Plan

## Overview

Kolekcja (klasery → strony → pozycje) zależy dziś 1:1 od crowncaps.info: pozycja trzyma tylko `capId`, a nazwa/kraj/zdjęcie/szczegóły dociągane są na żywo z API. Gdy crowncaps usunie kapsel, zmieni jego ID albo (najgroźniejsze) przypisze ID do innego kapsla — Twoja kolekcja po cichu się rozjeżdża.

Plan zrywa tę zależność dwoma krokami: **(A)** trwały snapshot identyfikujący każdy wstawiony kapsel (Room + Firestore), z którego renderują się klasery; **(B)** silnik weryfikacji, który przez „fingerprint" wykrywa usunięcie / podmianę tożsamości / edycję i pozwala Ci ręcznie rozstrzygnąć rozjazdy.

## Current State Analysis

- **Pozycja = goły `capId`.** `CapPosition(capId: Long)` w Room; `CapPositionDocument {firestoreId, binderPageFirestoreId, position, capId}` w Firestore (`CapPositionFirestoreService`). Brak jakichkolwiek danych opisowych.
- **Snapshot już częściowo istnieje, ale ubogi i lokalny.** `cap_cache (cap_id, country, image_url)` — `BindersViewModel.loadCapInfo` renderuje z niego (offline), z API dociąga tylko braki/bez-zdjęcia. **Ale**: brak nazwy i fingerprintu, oraz `cap_cache` **nie jest synchronizowany do Firestore** → ginie po reinstalacji i odtwarza się z API (czyli znów zależność).
- **Dodawanie kapsla ma pełne dane pod ręką.** `CapDetailViewModel.saveAssignment` woła `capPositionRepository.assign(pageId, position, capId)`, mając w `current.cap` pełny `CapExtended` (`createdAt`, `createdBy`, `country`, `imageUrl`). Dziś zapisuje tylko kraj (`capCacheRepository.upsert`). **Przechwycenie pełnego fingerprintu przy dodawaniu jest darmowe.**
- **API kapsla jest publiczne** (`GET /api/v1/caps/{id}` → 200 bez auth, ~2,5 KB). Brak stabilnego klucza alternatywnego (slug/UUID/EAN) — tylko numeryczne `id`. Ale są pola na fingerprint: `createdAt` (niezmienne), `createdBy.id`, `updatedAt` (tanie wykrycie zmiany), `imageUrl` (zawiera hash treści).
- **Room v6**, czysty wzorzec migracji `ALTER TABLE ADD COLUMN` (`MIGRATION_5_6`). `FirestoreRestoreUseCase` odtwarza klasery/strony/pozycje z Firestore po reinstalacji.
- **Skala:** 4126 wstawionych kapsli (`capPositionRepository.getTotalCount()`).

## Desired End State

- Każda wstawiona pozycja ma trwały snapshot (`nazwa, kraj, imageUrl, createdAt, createdBy.id, updatedAt`) w Room **i** Firestore. Klasery, statystyki i mapa renderują się ze snapshotu — bez sieci.
- Po reinstalacji i przywróceniu z Firestore kolekcja wyświetla się w całości offline, łącznie z nazwami/zdjęciami.
- Silnik weryfikacji rozpoznaje trzy stany rozjazdu: **usunięty** (404), **podmieniony** (createdAt/createdBy ≠ snapshot), **zmieniony** (updatedAt nowsze / inne zdjęcie / inny kraj). Działa w trzech trybach: jednorazowy backfill w tle po aktualizacji, pasywny inkrementalny ~50/sesję, ręczny pełny skan — wszystkie throttlowane i wznawialne.
- W menu konta jest odznaka „X do przejrzenia" i ekran przeglądu rozjazdów z akcjami: **zachowaj snapshot / zaakceptuj nowy / odepnij**.
- **Weryfikacja:** dodaj kapsel offline po pierwszym renderze → widoczny bez sieci; reinstalacja → pełne odtworzenie offline; podmiana danych w katalogu → rozjazd zgłoszony, decyzja po stronie użytkownika.

### Key Discoveries:

- `CapDetailViewModel.kt:124` — punkt przechwycenia snapshotu (pełny `CapExtended` dostępny).
- `BindersViewModel.kt:151-180` (`loadCapInfo`) + `:182` (`CapCache.toCap`) — render ze snapshotu; rozszerzyć o nazwę, nie nadpisywać snapshotu z API.
- `CapCacheDao.kt` — wzorzec `upsertFull` (UPSERT z `ON CONFLICT`); dodać `upsertSnapshot` i zapytania weryfikacyjne.
- `CciDatabase.kt` — `version = 6`, lista migracji; dodać `MIGRATION_6_7`.
- `CapPositionFirestoreService.kt:47` (`CapPositionDocument`) + `CapPositionRepository.assign` (`:31`) — rozszerzyć o pola snapshotu.
- `FirestoreRestoreUseCase.kt:112` — odtwarzanie pozycji; zapisać snapshot do `cap_cache`.
- `model/CapExtended.kt` — dodać `updatedAt` (jest w JSON, brak w modelu).

## What We're NOT Doing

- **Filtr roku** (z crowncaps map) — pomijany.
- **Własna kopia bajtów zdjęć / Firebase Storage** (opcja D) — odłożone; trzymamy `imageUrl` (z hashem), nie pliki.
- **WorkManager / okresowy job w tle** — odłożony; bieżąca weryfikacja jest pasywna + ręczna.
- **Eksport JSON** (opcja E) — poza zakresem.
- **Migracja istniejących pozycji do pełnego fingerprintu od ręki** — robi to backfill w tle, stopniowo.

## Implementation Approach

Dwie fazy. **Faza 1** daje trwałość (snapshot + sync + render) i jest samodzielnie wartościowa — nawet bez weryfikacji kolekcja przestaje zależeć 1:1 od API i przeżywa reinstalację. **Faza 2** dokłada silnik weryfikacji (jeden rdzeń „pobierz detal → zapisz/porównaj → status", napędzany trzema trybami) oraz UI przeglądu rozjazdów z akcjami naprawczymi.

Zasada przewodnia: **renderuj ze snapshotu, API jest doradcze, snapshot zmieniaj tylko przez świadomy przepływ weryfikacji** — nic nie podmienia się po cichu.

## Critical Implementation Details

- **Fingerprint świeżości po weryfikacji wraca też do Firestore.** Po udanej weryfikacji kapsla aktualizujemy snapshot nie tylko w `cap_cache` (Room), ale i w dokumencie pozycji Firestore — inaczej baseline fingerprintu nie przetrwa reinstalacji i każdy restore zaczynałby od zera.
- **Backfill i weryfikacja to ten sam rdzeń.** Różni je tylko sterowanie: backfill = pełny przebieg bez wcześniejszego fingerprintu (tylko zapis baseline, bez alarmów), weryfikacja = przebieg z istniejącym fingerprintem (porównanie). Rdzeń musi traktować „brak poprzedniego fingerprintu" jako zapis-bez-porównania.
- **Uprzejmość wobec crowncaps.** Wszystkie przebiegi przez wspólny ogranicznik (semafor jak w `StatisticsViewModel`/`BindersViewModel`) + throttle tempa; pełny skan i backfill preferują sieć unmetered. To jedyna relacja z cudzym serwerem.
- **Wznawialność po `lastVerifiedAt`.** Kursor = pozycje z najstarszym (lub NULL) `last_verified_at`; przerwany przebieg wznawia się naturalnie, bez osobnego stanu.

---

## Phase 1: Snapshot (A) — model, przechwycenie, synchronizacja, render

### Overview

Rozszerzyć snapshot o nazwę i fingerprint, przechwytywać go przy dodawaniu kapsla, synchronizować do Firestore i odtwarzać przy restore, oraz renderować klasery ze snapshotu. Po tej fazie kolekcja jest trwała i niezależna od API przy wyświetlaniu.

### Changes Required:

#### 1. Model szczegółów kapsla

**File**: `app/src/main/java/pl/sroki/cci/android/model/CapExtended.kt`

**Intent**: Udostępnić `updatedAt`, którego dziś brak w modelu, by móc tanio wykrywać zmianę kapsla.

**Contract**: Dodać pole `updatedAt: Instant` (deserializowane `InstantSerializer`, jak `createdAt`). JSON zawiera `updatedAt`.

#### 2. Encja snapshotu + migracja Room

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/entity/CapCache.kt`, `.../local/CciDatabase.kt`

**Intent**: Poszerzyć `cap_cache` o nazwę i pola fingerprintu/weryfikacji, tak by jeden wiersz w pełni identyfikował kapsel i niósł stan weryfikacji.

**Contract**: Nowe kolumny: `name TEXT NOT NULL DEFAULT ''`, `created_at TEXT`, `created_by_id INTEGER`, `updated_at TEXT`, `last_verified_at INTEGER` (epoch ms, nullable), `catalog_status TEXT NOT NULL DEFAULT 'unknown'` (wartości: `unknown|ok|updated|swapped|missing`). Bump `version = 7` + `MIGRATION_6_7` (`ALTER TABLE cap_cache ADD COLUMN ...`) zarejestrowana w buildzie bazy obok pozostałych.

#### 3. Zapis snapshotu w warstwie cache

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/dao/CapCacheDao.kt`, `.../data/CapCacheRepository.kt`

**Intent**: Dać metodę zapisującą komplet snapshotu (UPSERT) bez gubienia pól weryfikacji, oraz metodę zapisu samego wyniku weryfikacji.

**Contract**: `upsertSnapshot(capId, name, country, imageUrl, createdAt, createdById, updatedAt)` (UPSERT jak `upsertFull`, nie ruszający `last_verified_at`/`catalog_status`); `markVerified(capId, status, verifiedAt)`. Repository eksponuje odpowiedniki.

#### 4. Przechwycenie snapshotu przy dodawaniu kapsla

**File**: `app/src/main/java/pl/sroki/cci/android/ui/catalog/caps/detail/CapDetailViewModel.kt`, `.../data/CapPositionRepository.kt`

**Intent**: W momencie przypisania kapsla do klasera zapisać pełny snapshot z już załadowanego `CapExtended` (darmowo) zamiast samego kraju.

**Contract**: `saveAssignment` zastępuje `capCacheRepository.upsert(capId, country)` zapisem pełnego snapshotu z `current.cap`. `CapPositionRepository.assign(...)` przyjmuje dane snapshotu i przekazuje je do `scheduleCreate` (Firestore). `reassign` zachowuje istniejący snapshot.

#### 5. Snapshot w Firestore (sync + restore)

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/firestore/CapPositionFirestoreService.kt`, `.../data/FirestoreRestoreUseCase.kt`

**Intent**: Utrwalić snapshot w dokumencie pozycji, by przeżył reinstalację, i odtworzyć go do `cap_cache` przy restore.

**Contract**: `CapPositionDocument` zyskuje pola `name, country, imageUrl, createdAt, createdById, updatedAt` (wszystkie opcjonalne — istniejące 4126 dokumentów ich nie ma, restore toleruje braki). `scheduleCreate` zapisuje je. `FirestoreRestoreUseCase` po odtworzeniu pozycji robi `upsertSnapshot` do `cap_cache` dla pól, które są obecne.

#### 6. Render klaserów ze snapshotu

**File**: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersViewModel.kt`

**Intent**: Wzbogacić render o nazwę i nie nadpisywać istniejącego snapshotu danymi z API.

**Contract**: `CapCache.toCap()` mapuje też `name` (jako `description`/`product` wedle potrzeb UI). `loadCapInfo` traktuje snapshot jako autorytatywny — API tylko dla pozycji bez snapshotu.

### Success Criteria:

#### Automated Verification:

- Kompilacja przechodzi: `gradlew :app:compileDebugKotlin`
- Testy jednostkowe przechodzą: `gradlew :app:testDebugUnitTest`
- ktlint przechodzi: `gradlew :app:ktlintCheck`
- Test migracji 6→7 (Room) przechodzi
- `FirestoreRestoreTest` rozszerzony o pola snapshotu przechodzi

#### Manual Verification:

- Dodanie kapsla do klasera zapisuje nazwę+kraj+zdjęcie+fingerprint (widoczne offline po wyłączeniu sieci)
- Po reinstalacji i przywróceniu z Firestore klasery renderują się w całości offline (nazwy + zdjęcia)
- Istniejące pozycje (bez snapshotu w Firestore) nadal się wyświetlają, bez crasha
- Brak regresji w Klaserach/Statystykach/Mapie

**Implementation Note**: Po tej fazie i przejściu automatycznej weryfikacji — pauza na ręczne potwierdzenie (zwłaszcza render offline i restore) przed Fazą 2.

---

## Phase 2: Silnik weryfikacji (B) + przegląd rozjazdów

### Overview

Jeden rdzeń weryfikacji napędzany trzema trybami (auto-backfill po aktualizacji, pasywny inkrementalny, ręczny pełny skan) wykrywa rozjazdy przez fingerprint i utrwala status; menu konta zyskuje odznakę i ekran przeglądu z akcjami naprawczymi.

### Changes Required:

#### 1. Rdzeń weryfikacji

**File**: `app/src/main/java/pl/sroki/cci/android/data/CollectionVerifier.kt` (nowy)

**Intent**: Dla pojedynczego `capId`: pobrać detal, zbudować świeży fingerprint, porównać ze snapshotem i zapisać status — z `last_verified_at`. Backfill = przebieg bez poprzedniego fingerprintu (tylko zapis baseline).

**Contract**: `verify(capId): CatalogStatus`. Logika 3-poziomowa: 404/not-found → `missing` (zachowaj snapshot); `createdAt` lub `createdById` ≠ snapshot → `swapped`; `updatedAt` nowsze / inny `imageUrl` / inny kraj/nazwa → `updated`; inaczej → `ok`. Po sukcesie: `markVerified` w Room **oraz** aktualizacja snapshotu w dokumencie pozycji Firestore. Wspólny ogranicznik współbieżności + throttle. Brak poprzedniego fingerprintu → zapis bez alarmu.

#### 2. Tryby uruchamiania

**File**: `app/src/main/java/pl/sroki/cci/android/data/CollectionVerifier.kt`, mały store preferencji (wzorzec `PurchasedCapsLocalStore`/`SessionRepository`)

**Intent**: Wystawić trzy sterowniki dzielące rdzeń, wszystkie wznawialne po `last_verified_at`.

**Contract**: `runFullScan(onProgress, cancellationToken)` — pełny przebieg (ręczny i auto-backfill), throttlowany, anulowalny/wznawialny, preferuje unmetered. `runIncremental(limit = 50)` — pozycje z najstarszym/NULL `last_verified_at`. Flaga „ostatnio zbackfillowany versionCode" w prefs steruje jednorazowym auto-backfillem.

#### 3. Auto-backfill po aktualizacji

**File**: `app/src/main/java/pl/sroki/cci/android/CCIApplication.kt` (lub inicjalizator), prefs

**Intent**: Po pierwszym uruchomieniu po aktualizacji odpalić jednorazowo `runFullScan` w tle, ustanawiając baseline fingerprintu dla 4126 pozycji.

**Contract**: Jeśli zapisany versionCode < bieżący → uruchom backfill w tle (poza main thread, throttle), po zakończeniu zapisz bieżący versionCode. Wznawia się przy kolejnym starcie, jeśli przerwany.

#### 4. Pasywna weryfikacja inkrementalna

**File**: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersViewModel.kt` lub `.../ui/statistics/StatisticsViewModel.kt`

**Intent**: Przy wejściu w Klasery/Statystyki zweryfikować ~50 najdawniej sprawdzanych pozycji, rozkładając koszt na sesje.

**Contract**: Wywołanie `runIncremental(50)` w `init`/`load` (jednokrotnie na wejście), ciche, bez blokowania UI.

#### 5. Odznaka + ekran przeglądu rozjazdów (menu konta)

**File**: `app/src/main/java/pl/sroki/cci/android/ui/.../CollectionVerificationScreen.kt` (+ ViewModel, trasa w `Screen.kt`/`MainActivity.kt`), wpięcie w menu konta + odznaka

**Intent**: W menu konta pokazać licznik „X do przejrzenia" i listę kapsli ze statusem `missing/swapped/updated`; tap w pozycję prowadzi do szczegółów kapsla, gdzie podejmujesz decyzję. Plus ręczny pełny skan.

**Contract**: Odznaka „X do przejrzenia" = liczba pozycji w stanie innym niż `ok/unknown`. Lista pogrupowana wg statusu (miniatura + snapshot vs świeże dane + status); tap → nawigacja do szczegółów kapsla (jednolite miejsce decyzji, pkt 7). Przycisk „Zweryfikuj całość" (`runFullScan` z paskiem postępu, anuluj/wznów).

#### 6. Oznaczenie rozjazdu w Klaserach (czerwona pogrubiona czcionka, propagacja w górę)

**File**: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersViewModel.kt`, `.../ui/binders/BindersScreen.kt`

**Intent**: Kapsel wymagający uwagi ma być widoczny od razu podczas przeglądania kolekcji — bez wchodzenia w menu konta — a oznaczenie ma propagować w górę, by łatwo trafić w głąb.

**Contract**: `BindersViewModel` wystawia per-cap `catalog_status` (z `cap_cache`) w stanie UI. W `BindersScreen`: etykieta wiersza kapsla **czerwona + pogrubiona** gdy status ∈ `{missing, swapped, updated}`; etykieta strony **czerwona + pogrubiona** gdy zawiera ≥1 oflagowany kapsel; etykieta klasera **czerwona + pogrubiona** gdy zawiera ≥1 oflagowany kapsel (propagacja kapsel → strona → klaser). Tap w kapsel korzysta z istniejącej nawigacji do szczegółów.

#### 7. Decyzja z poziomu szczegółów kapsla

**File**: `app/src/main/java/pl/sroki/cci/android/ui/catalog/caps/detail/CapDetailViewModel.kt`, `.../detail/CapDetailScreen.kt`

**Intent**: Gdy otwarty kapsel jest oflagowany, pokazać baner z opisem rozjazdu i pozwolić rozstrzygnąć na miejscu — ekran i tak ma świeży `CapExtended`, więc „zaakceptuj nowy" jest darmowe.

**Contract**: Jeśli `catalog_status` kapsla ∈ `{missing, swapped, updated}` → baner u góry (snapshot vs świeże dane) z akcjami: **zachowaj snapshot** (status→`ok`/rozstrzygnięty), **zaakceptuj nowy** (`upsertSnapshot` z bieżącego `CapExtended`, status→`ok`), **odepnij** (`capPositionRepository.unassign`). `missing` (404) → baner „usunięty z katalogu", akcje „zachowaj"/„odepnij". Po akcji odśwież odznakę i oznaczenia w Klaserach.

### Success Criteria:

#### Automated Verification:

- Kompilacja przechodzi: `gradlew :app:compileDebugKotlin`
- Testy jednostkowe przechodzą: `gradlew :app:testDebugUnitTest`
- ktlint przechodzi: `gradlew :app:ktlintCheck`
- `CollectionVerifierTest`: 404→`missing`, zmiana `createdAt`/`createdById`→`swapped`, nowsze `updatedAt`/inny `imageUrl`→`updated`, brak zmian→`ok`, brak poprzedniego fingerprintu→zapis bez alarmu
- Test sterownika: `runIncremental` wybiera pozycje wg najstarszego `last_verified_at`

#### Manual Verification:

- Po aktualizacji backfill w tle ustanawia fingerprint (rosnący `last_verified_at`), bez zauważalnego obciążenia UI
- Wejście w Klasery/Statystyki uruchamia cichy przebieg ~50 pozycji
- Ręczny „Zweryfikuj całość" pokazuje postęp, daje się anulować i wznowić
- Sztucznie wywołany rozjazd (np. podmieniony snapshot w bazie) pojawia się w przeglądzie z właściwym statusem
- Oflagowany kapsel w Klaserach jest czerwony + pogrubiony; strona i klaser go zawierające również (propagacja w górę)
- Z poziomu szczegółów kapsla można rozstrzygnąć rozjazd (zachowaj / zaakceptuj / odepnij)
- Akcje zachowaj / zaakceptuj / odepnij działają i aktualizują odznakę oraz oznaczenia w Klaserach
- Tempo zapytań jest grzecznościowe (brak nawały do crowncaps)

**Implementation Note**: Pauza na ręczne potwierdzenie po automatycznej weryfikacji — zwłaszcza zachowanie backfillu w tle i akcje naprawcze.

---

## Testing Strategy

### Unit Tests:

- `CollectionVerifier`: każdy z 3 poziomów + baseline-bez-porównania (mockk na `CapsRepository`/`CapCacheRepository`).
- Wybór pozycji do weryfikacji inkrementalnej wg `last_verified_at`.
- `CapCacheDao.upsertSnapshot` / `markVerified` (UPSERT nie gubi pól weryfikacji).
- Migracja Room 6→7 (test migracyjny).
- `FirestoreRestoreTest`: odtworzenie snapshotu do `cap_cache`, tolerancja braku pól w starych dokumentach.

### Integration Tests:

- Dodanie kapsla → snapshot w Room i Firestore → reinstalacja (restore) → render offline.

### Manual Testing Steps:

1. Dodaj kapsel, wyłącz sieć, wejdź w Klasery — nazwa/zdjęcie/kraj widoczne.
2. Odinstaluj, zainstaluj, przywróć z Firestore — kolekcja kompletna offline.
3. Podmień ręcznie fingerprint jednego kapsla w bazie → weryfikacja zgłasza `swapped`.
4. Wskaż nieistniejący `capId` → `missing`, pozycja zostaje, oznaczona.
5. Wykonaj akcje naprawcze, sprawdź odznakę.

## Performance Considerations

- Pełny skan/backfill 4126 ≈ ~10 MB / kilka–kilkanaście minut przy grzecznościowym tempie — dlatego w tle, throttlowany, wznawialny, preferuje unmetered.
- Inkrementalny ~50/sesję: ~125 KB, kilka sekund — niezauważalne.
- Weryfikacja czyta `last_verified_at` jako kursor — bez osobnego stanu postępu.

## Migration Notes

- Room 6→7: `ALTER TABLE cap_cache ADD COLUMN` — bezpieczne, addytywne.
- Firestore: nowe pola `CapPositionDocument` opcjonalne; istniejące 4126 dokumentów bez nich działają (render ze snapshotu Room, a brakujące pola uzupełnia backfill, który dopisuje je też do Firestore).
- Brak destrukcyjnych zmian; rollback = poprzedni APK (dane addytywne nie szkodzą starszej wersji poza ignorowaniem nowych kolumn/pól).

## References

- Change identity: `context/changes/collection-resilience/change.md`
- Punkt przechwycenia snapshotu: `app/src/main/java/pl/sroki/cci/android/ui/catalog/caps/detail/CapDetailViewModel.kt:124`
- Render ze snapshotu: `app/src/main/java/pl/sroki/cci/android/ui/binders/BindersViewModel.kt:151`
- Wzorzec migracji: `app/src/main/java/pl/sroki/cci/android/data/datasource/local/CciDatabase.kt` (`MIGRATION_5_6`)
- Sync/restore Firestore: `app/src/main/java/pl/sroki/cci/android/data/FirestoreRestoreUseCase.kt:112`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Snapshot (A) — model, przechwycenie, synchronizacja, render

#### Automated

- [x] 1.1 Kompilacja przechodzi: `gradlew :app:compileDebugKotlin` — 3468cdf
- [x] 1.2 Testy jednostkowe przechodzą: `gradlew :app:testDebugUnitTest` — 3468cdf
- [x] 1.3 ktlint przechodzi: `gradlew :app:ktlintCheck` — 3468cdf
- [x] 1.4 Test migracji 6→7 (Room) przechodzi
- [x] 1.5 `FirestoreRestoreTest` rozszerzony o pola snapshotu przechodzi

#### Manual

- [x] 1.6 Dodanie kapsla zapisuje pełny snapshot, widoczny offline
- [x] 1.7 Po reinstalacji + restore klasery renderują się offline (nazwy + zdjęcia)
- [x] 1.8 Istniejące pozycje bez snapshotu w Firestore wyświetlają się bez crasha
- [x] 1.9 Brak regresji w Klaserach/Statystykach/Mapie

### Phase 2: Silnik weryfikacji (B) + przegląd rozjazdów

#### Automated

- [x] 2.1 Kompilacja przechodzi: `gradlew :app:compileDebugKotlin` — b8b504c
- [x] 2.2 Testy jednostkowe przechodzą: `gradlew :app:testDebugUnitTest` — b8b504c
- [x] 2.3 ktlint przechodzi: `gradlew :app:ktlintCheck` — b8b504c
- [x] 2.4 `CollectionVerifierTest`: 3 poziomy + baseline-bez-porównania
- [x] 2.5 Test wyboru pozycji wg najstarszego `last_verified_at`

#### Manual

- [x] 2.6 Backfill w tle po aktualizacji ustanawia fingerprint bez obciążenia UI
- [x] 2.7 Pasywny przebieg ~50/sesję przy wejściu w Klasery/Statystyki
- [x] 2.8 Ręczny pełny skan: postęp, anulowanie, wznawianie
- [x] 2.9 Sztuczny rozjazd pojawia się w przeglądzie z właściwym statusem
- [x] 2.10 Oflagowany kapsel/strona/klaser w Klaserach: czerwona pogrubiona czcionka (propagacja w górę)
- [x] 2.11 Rozstrzygnięcie rozjazdu z poziomu szczegółów kapsla (zachowaj / zaakceptuj / odepnij) działa i aktualizuje odznakę + oznaczenia
- [x] 2.12 Tempo zapytań grzecznościowe (brak nawały do crowncaps)

---

## Epilog

**Status implementacji**: obie fazy wdrożone na branchu `main`.

| Faza | Commit | Co dostarcza |
|---|---|---|
| 1 — Snapshot | 3468cdf | Room v7 (migracja 6→7), `CapCache` z fingerprintem, `upsertSnapshot`, przechwycenie przy dodawaniu, sync Firestore, restore offline, render ze snapshotu |
| 2 — Weryfikacja | b8b504c | `CollectionVerifier` (3-poziomowy rdzeń), auto-backfill po aktualizacji, inkrementalny ~50/sesję, `CollectionVerificationScreen` + odznaka, oznaczenia w Klaserach (propagacja kapsel→strona→klaser), akcje naprawcze w `CapDetailScreen` |

**Automatyczne kryteria sukcesu**: kompilacja + unit testy + ktlint przechodzą na obu commitach (1.1–1.3 ✓, 2.1–2.3 ✓).

**Wszystkie kryteria sukcesu potwierdzone** — zmiana gotowa do archiwizacji.

Uruchom `/10x-archive collection-resilience` aby zamknąć zmianę.

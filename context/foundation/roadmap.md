---
project: "CCI Android — wersja prywatna"
version: 4
status: done
created: 2026-06-11
updated: 2026-06-15
prd_version: 1
main_goal: low-complexity
top_blocker: none
---

# Roadmap: CCI Android — wersja prywatna

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.
> v2 (2026-06-15): F-01, F-02, F-03 i S-02 oznaczone `done`; S-01 awansowane do `ready`.
> v3 (2026-06-15): S-01, S-03, S-04 oznaczone `done` — wszystkie roadmap items zrealizowane.
> v4 (2026-06-15): S-05..S-07 dodane i oznaczone `done` — home-screen-redesign, advanced-search, api-validation.

## Vision recap

Aplikacja Android przeglądająca bazę crowncaps.info rozbudowywana o dwie nowe możliwości:
(1) odpowiedź na pytanie "czy mam już ten kapsel?" bezpośrednio w sklepie — przez zalogowanie
kontem crowncaps.info i wyeksponowanie pola `isInCollection` zwracanego przez API;
(2) zastąpienie Excela do śledzenia fizycznej lokalizacji kapsli w klaserach — lokalną bazą
danych z hierarchią klaser → strona → pozycja, zarządzaną wyłącznie na urządzeniu.

Kluczowa właściwość odróżniająca produkt od samej strony crowncaps.info: metadane z API
crowncaps.info są połączone z fizyczną lokalizacją kapsla w klaserze. Sama strona nie oferuje
ani statusu posiadania bez logowania, ani zarządzania klaserami.

## North star

**S-01: kolekcjoner w sklepie widzi status posiadania i oznacza kapsel jako kupiony** — dowodzi
Primary Success Criterion #1: użytkownik widzi `isInCollection = true/false` w wynikach
wyszukiwania i jednym tapnięciem rejestruje zakup, co udowadnia, że API crowncaps.info obsługuje
autentykację i zwraca prawdziwy `isInCollection` dla zalogowanego użytkownika.

> "North star" oznacza tutaj: najmniejszy end-to-end przepływ, którego dostarczenie udowodniłoby,
> że rdzeń produktu działa — umieszczony tak wcześnie, jak pozwalają Prerequisites, bo wszystko
> inne ma sens tylko jeśli ten przepływ działa. S-01 jest teraz `ready` — F-01 i F-02 spełnione.

## At a glance

| ID   | Change ID                  | Outcome (user can…)                                                              | Prerequisites | PRD refs                                       | Status   |
|------|----------------------------|----------------------------------------------------------------------------------|---------------|------------------------------------------------|----------|
| F-01 | auth-scaffold              | (foundation) zalogować się kontem crowncaps.info i wywoływać auth'd endpointy    | —             | FR-001, FR-002, FR-004, FR-005, FR-006         | done     |
| F-02 | room-local-data            | (foundation) lokalnie trwałe dane klaserów i kapsli oczekujących są dostępne     | —             | FR-008, FR-009, FR-010, FR-011, FR-012, FR-013 | done     |
| F-03 | firestore-sync             | (foundation) dane klaserów/stron/pozycji synchronizowane z Firestore — backup    | F-02          | —                                              | done     |
| S-01 | shop-check-and-mark-bought | widzieć status posiadania przy kapslu i oznaczyć go jako kupiony                 | F-01, F-02    | FR-001, FR-003, FR-007, FR-008, US-01          | done     |
| S-02 | binder-management          | tworzyć i usuwać klasery oraz dodawać do nich strony                             | F-02, F-03    | FR-010, FR-011                                 | done     |
| S-03 | cataloging-flow            | katalogować kapsel z zakładki oczekujących do pozycji w klaserze                 | S-01, S-02    | FR-008, FR-009, FR-012, US-02                  | done     |
| S-04 | binder-fill-stats          | widzieć zapełnienie klaserów — ile wolnych pozycji na każdej stronie             | S-02, S-03    | FR-013                                         | done     |
| S-05 | home-screen-redesign       | korzystać ze stałego pola wyszukiwania i nowych przycisków nawigacji na Home     | —             | —                                              | done     |
| S-06 | advanced-search            | wyszukiwać kapsle wg wielu filtrów (ID, tekst, kraj, producent)                  | —             | —                                              | done     |
| S-07 | api-validation             | (research) zweryfikowane działanie i zgodność 7 endpointów REST API              | —             | —                                              | done     |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives
in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                                  | Chain                                                    | Note                                                              |
|--------|----------------------------------------|----------------------------------------------------------|-------------------------------------------------------------------|
| A      | Autentykacja + weryfikacja posiadania  | `F-01` ✓ → `S-01`                                       | S-01 [ready]; zasila Stream B przy `S-03`                        |
| B      | Struktura klaserów + katalogowanie     | `F-02` ✓ → `F-03` ✓ → `S-02` ✓ → `S-03` → `S-04`      | S-03 łączy ze Streamu A (czeka na S-01)                          |

## Baseline

What's already in place in the codebase as of 2026-06-15 (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** present — Jetpack Compose BOM 2026.05.01, 8+ ekranów, architektura MVVM+Repository
- **Backend / API:** present — Retrofit 3.0.0, 3 serwisy API, 7 anonimowych endpointów + auth endpointy (Sanctum)
- **Data:** present — Room v7, CciDatabase (PendingCap, Binder, BinderPage, CapPosition, CapCache, CountryFlag), Firestore sync dual-write
- **Auth:** present — CSRF + cookie (Laravel Sanctum), BearerTokenInterceptor, PersistentCookieJar, SessionRepository (SharedPreferences)
- **Deploy / infra:** partial — GitHub Actions CI (test+lint na push do main); signing release obecny; brak automatycznego deploy APK
- **Observability:** partial — android.util.Log w AuthRepository i CapsRepository; brak Timber

## Foundations

### F-01: Auth scaffold

- **Outcome:** (foundation) użytkownik może zalogować się kontem crowncaps.info; sesja persystowana przez OkHttp CookieJar (`XSRF-TOKEN` + `crowncapsinfo-session`); interceptor CSRF dodaje `X-XSRF-TOKEN` header do POST/PUT/DELETE; anonimowe endpointy (przeglądanie, wyszukiwanie, szczegóły) pozostają niezmienione.
- **Change ID:** `auth-scaffold`
- **PRD refs:** FR-001, FR-002 (backward compat — auth nie blokuje anon browsing), FR-004, FR-005, FR-006 (regression: preserved FRs muszą działać bez zmian po dodaniu interceptora)
- **Unlocks:** S-01 (ownership status + mark-as-bought przepływ)
- **Prerequisites:** —
- **Parallel with:** F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. Laravel Sanctum cookie mode zweryfikowany — `GET /sanctum/csrf-cookie` → `POST /auth/login` → sesja. PersistentCookieJar persystuje cookies między sesjami.
- **Status:** done

### F-02: Room local data layer

- **Outcome:** (foundation) schemat Room v7: `PendingCap` (dla S-01), `Binder` + `BinderPage` (dla S-02), `CapPosition` (dla S-03/S-04); DAO + Repository; reguła unikalności slotu egzekwowana na poziomie DB constraint.
- **Change ID:** `room-local-data`
- **PRD refs:** FR-008, FR-009, FR-010, FR-011, FR-012, FR-013
- **Unlocks:** S-01 (potrzebuje `PendingCap`), S-02 (potrzebuje `Binder`+`BinderPage`), S-03 (potrzebuje `CapPosition`), S-04 (potrzebuje `CapPosition`)
- **Prerequisites:** —
- **Parallel with:** F-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. Schemat Room v7 obejmuje wszystkie encje domenowe; reguły unikalności zakodowane w DB constraints.
- **Status:** done

### F-03: Firestore sync

- **Outcome:** (foundation) dane klaserów (`Binder`), stron (`BinderPage`) i pozycji kapsli (`CapPosition`) synchronizowane z Firebase Firestore; Room offline-first — zapis do Room i Firestore jednocześnie, odczyt zawsze z Room; snapshot kolekcji + fingerprint umożliwiają wykrywanie rozbieżności.
- **Change ID:** `firestore-sync`
- **PRD refs:** —
- **Unlocks:** S-02 (dane klaserów muszą być trwałe przed UI zarządzania), S-03, S-04
- **Prerequisites:** F-02
- **Parallel with:** F-01, S-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. Darmowy tier Firestore (Spark) wystarcza dla prywatnej kolekcji. Uwaga: ta foundation wykracza poza PRD NFR ("dane wyłącznie na urządzeniu") — dodana jako resilience improvement.
- **Status:** done

## Slices

### S-01: Kolekcjoner w sklepie widzi status posiadania i oznacza kapsel jako kupiony

- **Outcome:** zalogowany użytkownik widzi przy każdym kapslu w wynikach wyszukiwania i listach status "posiadam" / "nie posiadam" (na podstawie `isInCollection` z API); jednym tapnięciem oznacza "kupuję" — `POST /data/catalog/caps/{id}/collection` rejestruje posiadanie w crowncaps.info + kapsel trafia lokalnie do zakładki "oczekuje na skatalogowanie"; niezalogowany widzi przycisk logowania zamiast statusu
- **Change ID:** `shop-check-and-mark-bought`
- **PRD refs:** FR-001, FR-003, FR-007, FR-008, US-01
- **Prerequisites:** F-01 (auth), F-02 (tabela `PendingCap`)
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. CookieJar persystuje sesję; isInCollection propagowane przez Paging 3.
- **Status:** done

### S-02: Kolekcjoner tworzy klasery i strony

- **Outcome:** użytkownik może dodać nowy klaser (nazwa: kontynent + numer), usunąć pusty klaser, dodać stronę do klasera (limit 15); zajęte klasery są chronione przed usunięciem; próba dodania 16. strony kończy się czytelnym komunikatem błędu
- **Change ID:** `binder-management`
- **PRD refs:** FR-010, FR-011
- **Prerequisites:** F-02 (tabele `Binder`+`BinderPage`), F-03
- **Parallel with:** S-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. Blokada usuwania klasera z kapslami egzekwowana w DAO-level guard i FK constraint.
- **Status:** done

### S-03: Kolekcjoner kataloguje kapsel z zakładki oczekujących do klasera

- **Outcome:** zalogowany użytkownik widzi zakładkę "oczekuje na skatalogowanie" z listą kapsli (zdjęcie + nazwa z crowncaps.info); tapnięciem wybiera kapsel i przypisuje go do (klaser, strona, pozycja); zajęte pozycje wyraźnie oznaczone i nieselektywne; pełna strona (35/35) oznaczona jako niedostępna; po zapisaniu kapsel znika z listy oczekujących; edycja istniejącego przypisania zwalnia starą pozycję automatycznie
- **Change ID:** `cataloging-flow`
- **PRD refs:** FR-008, FR-009, FR-012, US-02
- **Prerequisites:** S-01 (kapsle muszą trafiać do zakładki oczekujących), S-02 (klasery i strony muszą istnieć)
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. UI selector pozycji z LazyColumn i filtrowaniem zajętych slotów.
- **Status:** done

### S-04: Kolekcjoner widzi zapełnienie klaserów

- **Outcome:** użytkownik widzi ekran z listą klaserów; przy każdym klaserze widzi każdą stronę z liczbą wolnych i zajętych pozycji (każda strona ma 35 miejsc); widok obliczany lokalnie bez wywołań API
- **Change ID:** `binder-fill-stats`
- **PRD refs:** FR-013
- **Prerequisites:** S-02 (klasery i strony muszą istnieć), S-03 (kapsle muszą być przypisane)
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. Obliczenie lokalne z Room bez wywołań API.
- **Status:** done

### S-05: Home screen redesign

- **Outcome:** użytkownik widzi stałe pole wyszukiwania (QuickSearch) zawsze widoczne na HomeScreen oraz nowe przyciski nawigacji; przycisk Szukanie zaawansowane widoczny (choć initially disabled).
- **Change ID:** `home-screen-redesign`
- **PRD refs:** —
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. UI-only zmiana bez wpływu na logikę biznesową.
- **Status:** done

### S-06: Advanced search

- **Outcome:** użytkownik może wyszukiwać kapsle wg wielu filtrów jednocześnie: ID (Zawiera/Równe/Zaczyna się od), tekst, kraj (autocomplete), producent; wyniki paginowane (Paging 3), widoczne poniżej formularza; przycisk na HomeScreen odblokowany.
- **Change ID:** `advanced-search`
- **PRD refs:** —
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. API risk: endpoint `GET api/v1/caps?query=` może nie obsługiwać wszystkich parametrów filtrowania — zweryfikowane w S-07.
- **Status:** done

### S-07: API validation

- **Outcome:** (research) zweryfikowane poprawne działanie wszystkich 7 endpointów REST API crowncaps.info oraz zgodność odpowiedzi JSON z modelami Kotlin; udokumentowane odchylenia i workaroundy.
- **Change ID:** `api-validation`
- **PRD refs:** —
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Zrealizowane. Weryfikacja ręczna + przegląd modeli Kotlin vs odpowiedzi API.
- **Status:** done

## Backlog Handoff

| Roadmap ID | Change ID                  | Suggested issue title                                               | Ready for `/10x-plan` | Notes                                           |
|------------|----------------------------|---------------------------------------------------------------------|-----------------------|-------------------------------------------------|
| F-01       | auth-scaffold              | [Foundation] Scaffold autoryzacji crowncaps.info                    | —                     | Done. Pending `/10x-archive auth-scaffold`      |
| F-02       | room-local-data            | [Foundation] Room local data layer — schemat klaserów + PendingCap  | —                     | Done. Pending `/10x-archive room-local-data`    |
| F-03       | firestore-sync             | [Foundation] Firestore sync — backup klaserów/stron/pozycji         | —                     | Done. Pending `/10x-archive firestore-sync`     |
| S-01       | shop-check-and-mark-bought | Kolekcjoner: status posiadania i oznaczenie kupionego               | —                     | Done. Pending `/10x-archive shop-check-and-mark-bought` |
| S-02       | binder-management          | Kolekcjoner: tworzenie klaserów i stron                             | —                     | Done. Pending `/10x-archive binder-management`  |
| S-03       | cataloging-flow            | Kolekcjoner: katalogowanie kapsla do klasera                        | —                     | Done. Pending `/10x-archive cataloging-flow`    |
| S-04       | binder-fill-stats          | Kolekcjoner: zapełnienie klaserów                                   | —                     | Done. Pending `/10x-archive binder-fill-stats`  |

## Open Roadmap Questions

1. ~~**Czy API crowncaps.info obsługuje autentykację użytkownika?**~~ — **ROZWIĄZANE 2026-06-11**: Laravel Sanctum, tryb cookie. Szczegóły w F-01 "Auth API contract".
2. ~~**Czy API crowncaps.info udostępnia endpoint zapisu do kolekcji?**~~ — **ROZWIĄZANE 2026-06-11**: `POST /data/catalog/caps/{id}/collection` (dodaj) + `DELETE /data/catalog/caps/{id}/collection` (usuń). Fallback niepotrzebny.
3. **Czy crowncaps.info udostępnia API statystyk kolekcji użytkownika (liczba kapsli, podział wg kraju)?** — Owner: deweloper. Block: nie — FR-014 (nice-to-have) może być pominięte bez wpływu na MVP.

## Parked

- **Tryb offline dla sprawdzania isInCollection** — Why parked: PRD §Non-Goals — wymaga aktywnego połączenia z API; widok klaserów (dane lokalne) działa offline. Kandydat do v2.
- **Wersja publiczna** — Why parked: PRD §Non-Goals — osobny projekt, osobny shaping.
- **Rozpoznawanie kapsla ze zdjęcia przez AI** — Why parked: PRD §Non-Goals — PictureSearch pozostaje jako wybór kategorii wizualnych; AI image recognition to oddzielna, kosztowna decyzja.
- **Udostępnianie kolekcji innym użytkownikom** — Why parked: PRD §Non-Goals — aplikacja prywatna, jedno konto.
- **Usuwanie kapsla z kolekcji crowncaps.info z poziomu aplikacji** — Why parked: PRD §Non-Goals — MVP obsługuje tylko dodawanie.
- **FR-014: Statystyki kolekcji (liczba kapsli, podział wg kraju)** — Why parked: Priority: nice-to-have; dostępność API weryfikowana przed implementacją — endpoint może nie istnieć.

## Done

- **F-01: (foundation) zalogować się kontem crowncaps.info i wywoływać auth'd endpointy** — Implemented 2026-06-11. Pending `/10x-archive auth-scaffold`. Lesson: —.
- **F-02: (foundation) lokalnie trwałe dane klaserów i kapsli oczekujących są dostępne** — Implemented 2026-06-11. Pending `/10x-archive room-local-data`. Lesson: —.
- **F-03: (foundation) dane klaserów/stron/pozycji synchronizowane z Firestore** — Implemented 2026-06-11. Pending `/10x-archive firestore-sync`. Lesson: —.
- **S-02: użytkownik może tworzyć i usuwać klasery oraz dodawać do nich strony** — Implemented 2026-06-14. Pending `/10x-archive binder-management`. Lesson: —.
- **S-01: zalogowany użytkownik widzi status posiadania i oznacza kapsel jako kupiony** — Implemented 2026-06-15. Pending `/10x-archive shop-check-and-mark-bought`. Lesson: —.
- **S-03: zalogowany użytkownik kataloguje kapsel z zakładki oczekujących do klasera** — Implemented 2026-06-15. Pending `/10x-archive cataloging-flow`. Lesson: —.
- **S-04: użytkownik widzi zapełnienie klaserów — ile wolnych pozycji na każdej stronie** — Implemented 2026-06-15. Pending `/10x-archive binder-fill-stats`. Lesson: —.
- **S-05: Home screen redesign** — Archived 2026-06-15 → `context/archive/2026-06-11-home-screen-redesign/`. Lesson: —.
- **S-06: Advanced search** — Archived 2026-06-15 → `context/archive/2026-06-12-advanced-search/`. Lesson: —.
- **S-07: API validation** — Archived 2026-06-15 → `context/archive/2026-06-10-api-validation/`. Lesson: —.

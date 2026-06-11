---
project: "CCI Android — wersja prywatna"
version: 1
status: draft
created: 2026-06-11
updated: 2026-06-11
prd_version: 1
main_goal: low-complexity
top_blocker: none
---

# Roadmap: CCI Android — wersja prywatna

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

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
> inne ma sens tylko jeśli ten przepływ działa.

## At a glance

| ID   | Change ID                  | Outcome (user can…)                                                              | Prerequisites | PRD refs                                    | Status   |
|------|----------------------------|----------------------------------------------------------------------------------|---------------|---------------------------------------------|----------|
| F-01 | auth-scaffold              | (foundation) zalogować się kontem crowncaps.info i wywoływać auth'd endpointy    | —             | FR-001, FR-002, FR-004, FR-005, FR-006      | ready    |
| F-02 | room-local-data            | (foundation) lokalnie trwałe dane klaserów i kapsli oczekujących są dostępne     | —             | FR-008, FR-009, FR-010, FR-011, FR-012, FR-013 | ready |
| F-03 | firestore-sync             | (foundation) dane klaserów/stron/pozycji synchronizowane z Firestore — backup i odtwarzanie po utracie urządzenia | F-02 | — | proposed |
| S-01 | shop-check-and-mark-bought | widzieć status posiadania przy kapslu i oznaczyć go jako kupiony                  | F-01, F-02    | FR-001, FR-003, FR-007, FR-008, US-01       | proposed |
| S-02 | binder-management          | tworzyć i usuwać klasery oraz dodawać do nich strony                              | F-02, F-03    | FR-010, FR-011                              | proposed |
| S-03 | cataloging-flow            | katalogować kapsel z zakładki oczekujących do pozycji w klaserze                  | S-01, S-02    | FR-008, FR-009, FR-012, US-02               | proposed |
| S-04 | binder-fill-stats          | widzieć zapełnienie klaserów — ile wolnych pozycji na każdej stronie              | S-02, S-03    | FR-013                                      | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives
in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                                 | Chain                                       | Note                                                                           |
|--------|---------------------------------------|---------------------------------------------|--------------------------------------------------------------------------------|
| A      | Struktura klaserów + katalogowanie    | `F-02` → `F-03` → `S-02` → `S-03` → `S-04` | `S-03` joins Stream B at `S-01`; F-03 (Firestore backup) wymagane przed S-02  |
| B      | Autentykacja + weryfikacja posiadania | `F-01` → `S-01`                             | OQ-1/OQ-2 rozwiązane 2026-06-11; `S-01` wchodzi w `S-03` (Stream A)           |

## Baseline

What's already in place in the codebase as of 2026-06-11 (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** present — Jetpack Compose BOM 2025.05.01, 8 ekranów (Home, Countries, CountryCaps, Latest, QuickSearch, PictureSearch, PictureSearchCaps, CapDetail), architektura MVVM+Repository
- **Backend / API:** present — Retrofit 2.11, 3 serwisy API (CapApiService, CountryApiService, CategoryApiService), 7 endpointów, wszystkie HTTP 200
- **Data:** absent — brak Room, brak DataStore, brak SharedPreferences
- **Auth:** absent — wszystkie wywołania API anonimowe, brak interceptora, brak przechowywania tokenu
- **Deploy / infra:** absent — brak CI/CD, brak Dockerfile
- **Observability:** partial — tylko `println()` w `CapDetailView.kt:92`, brak frameworka logowania

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
- **Auth API contract (zweryfikowany 2026-06-11):**
  - `GET /sanctum/csrf-cookie` → 204; ustawia `XSRF-TOKEN` + `crowncapsinfo-session` w cookies
  - `POST /auth/login` + header `X-XSRF-TOKEN` + body `{"email":"...","password":"..."}` → sesja; błąd: `{"errors":{"email":["..."]}}`
  - `GET /data/users/current` → 200 zalogowany / 401 niezalogowany
  - `POST /logout`
  - `POST /data/catalog/caps/{id}/collection` → dodaj do kolekcji (brak body)
  - `DELETE /data/catalog/caps/{id}/collection` → usuń z kolekcji
- **Risk:** Laravel Sanctum cookie mode — Android wymaga CookieJar z persystencją między sesjami aplikacji (EncryptedSharedPreferences lub `PersistentCookieJar`). Brak Turnstile po stronie serwera — logowanie nie wymaga CAPTCHA tokena.
- **Status:** ready

### F-02: Room local data layer

- **Outcome:** (foundation) minimalny schemat Room: tabela `PendingCap` (cap_id — dla S-01), tabela `Binder` + `BinderPage` (klaser i strony — dla S-02), tabela `CapPosition` (klaser+strona+pozycja jako unique slot — dla S-03, S-04); DAO + Repository; reguła unikalności slotu egzekwowana na poziomie DB constraint. Nie zawiera logiki UI.
- **Change ID:** `room-local-data`
- **PRD refs:** FR-008, FR-009, FR-010, FR-011, FR-012, FR-013
- **Unlocks:** S-01 (potrzebuje `PendingCap`), S-02 (potrzebuje `Binder`+`BinderPage`), S-03 (potrzebuje `CapPosition`), S-04 (potrzebuje `CapPosition`)
- **Prerequisites:** —
- **Parallel with:** F-01
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Schemat musi przewidzieć reguły domenowe z góry: strona 1–15, pozycja 1–35, unikalność (binder_id, page, position). Błąd tu wymaga późniejszej migracji Room — jednorazowe przemyślenie schematu jest tańsze niż refactor.
- **Status:** ready

### F-03: Firestore sync

- **Outcome:** (foundation) dane klaserów (`Binder`), stron (`BinderPage`) i pozycji kapsli (`CapPosition`) są synchronizowane dwukierunkowo z Firebase Firestore; utrata urządzenia nie oznacza utraty danych kolekcji; Room pozostaje lokalnym cache (offline-first) — zapis do Room i Firestore jednocześnie, odczyt zawsze z Room.
- **Change ID:** `firestore-sync`
- **PRD refs:** —
- **Unlocks:** S-02 (klasery muszą być trwałe przed UI), S-03, S-04
- **Prerequisites:** F-02 (schemat Room musi istnieć; Firestore odzwierciedla te same encje)
- **Parallel with:** F-01, S-01 (Firestore nie blokuje auth flow)
- **Blockers:** —
- **Unknowns:** strategia pierwszej synchronizacji po instalacji (Firestore → Room pull); konflikt przy jednoczesnej edycji z dwóch urządzeń (MVP: last-write-wins).
- **Risk:** Firestore wymaga konta Google i konfiguracji `google-services.json`; darmowy tier (Spark): 1 GB storage, 50K reads/day, 20K writes/day — wystarczający dla prywatnej kolekcji. Offline-first z Room chroni przed przerwami sieciowymi.
- **Status:** proposed

## Slices

### S-01: Kolekcjoner w sklepie widzi status posiadania i oznacza kapsel jako kupiony

- **Outcome:** zalogowany użytkownik widzi przy każdym kapslu w wynikach wyszukiwania i listach status "posiadam" / "nie posiadam" (na podstawie `isInCollection` z API); jednym tapnięciem oznacza "kupuję" — `POST /data/catalog/caps/{id}/collection` rejestruje posiadanie w crowncaps.info + kapsel trafia lokalnie do zakładki "oczekuje na skatalogowanie"; niezalogowany widzi przycisk logowania zamiast statusu
- **Change ID:** `shop-check-and-mark-bought`
- **PRD refs:** FR-001, FR-003, FR-007, FR-008, US-01
- **Prerequisites:** F-01 (auth), F-02 (tabela `PendingCap`)
- **Parallel with:** S-02 (S-02 potrzebuje tylko F-02, niezależne od F-01)
- **Blockers:** —
- **Unknowns:** —
- **Risk:** North star — mechanizm auth jest zweryfikowany (Laravel Sanctum cookie), API zapis do kolekcji istnieje. Główne ryzyko implementacyjne: CookieJar musi persystować cookies między sesjami aplikacji; bez tego sesja ginie po restarcie.
- **Status:** proposed

### S-02: Kolekcjoner tworzy klasery i strony

- **Outcome:** użytkownik może dodać nowy klaser (nazwa: kontynent + numer), usunąć pusty klaser, dodać stronę do klasera (limit 15); zajęte klasery są chronione przed usunięciem; próba dodania 16. strony kończy się czytelnym komunikatem błędu
- **Change ID:** `binder-management`
- **PRD refs:** FR-010, FR-011
- **Prerequisites:** F-02 (tabele `Binder`+`BinderPage`), F-03 (dane muszą być backupowane przed UI zarządzania)
- **Parallel with:** S-01 (S-02 nie potrzebuje F-01 — niezależne od Streamu B; można zacząć gdy F-02 i F-03 gotowe)
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Prosta CRUD slice — niskie ryzyko. Ważne: blokada usuwania klasera z kapslami egzekwowana w Room (FK constraint lub DAO-level guard), nie tylko w UI — inaczej user może usunąć klasery przy bezpośrednim dostępie do DB.
- **Status:** proposed

### S-03: Kolekcjoner kataloguje kapsel z zakładki oczekujących do klasera

- **Outcome:** zalogowany użytkownik widzi zakładkę "oczekuje na skatalogowanie" z listą kapsli (zdjęcie + nazwa z crowncaps.info); tapnięciem wybiera kapsel i przypisuje go do (klaser, strona, pozycja); zajęte pozycje wyraźnie oznaczone i nieselektywne; pełna strona (35/35) oznaczona jako niedostępna; po zapisaniu kapsel znika z listy oczekujących; edycja istniejącego przypisania zwalnia starą pozycję automatycznie
- **Change ID:** `cataloging-flow`
- **PRD refs:** FR-008, FR-009, FR-012, US-02
- **Prerequisites:** S-01 (kapsle muszą trafiać do zakładki oczekujących), S-02 (klasery i strony muszą istnieć)
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** UI selector pozycji (klaser → strona → wolne sloty) jest najtrudniejszą częścią implementacji UI w tym projekcie — do 15 stron × 35 = 525 slotów per klaser; należy przewidzieć wydajne renderowanie listy slotów (LazyColumn z filtrowaniem zajętych).
- **Status:** proposed

### S-04: Kolekcjoner widzi zapełnienie klaserów

- **Outcome:** użytkownik widzi ekran z listą klaserów; przy każdym klaserze widzi każdą stronę z liczbą wolnych i zajętych pozycji (każda strona ma 35 miejsc); widok obliczany lokalnie bez wywołań API
- **Change ID:** `binder-fill-stats`
- **PRD refs:** FR-013
- **Prerequisites:** S-02 (klasery i strony muszą istnieć), S-03 (kapsle muszą być przypisane — inaczej widok zawsze zerowy)
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Czysto lokalne obliczenie — brak zewnętrznych zależności, niskie ryzyko. FR-013 ma Priority: must-have, nie nice-to-have — nie można pominąć.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                  | Suggested issue title                                               | Ready for `/10x-plan` | Notes                                   |
|------------|----------------------------|---------------------------------------------------------------------|-----------------------|-----------------------------------------|
| F-01       | auth-scaffold              | [Foundation] Scaffold autoryzacji crowncaps.info                    | yes                   | Uruchom `/10x-plan auth-scaffold`       |
| F-02       | room-local-data            | [Foundation] Room local data layer — schemat klaserów + PendingCap  | yes                   | Uruchom `/10x-plan room-local-data`     |
| F-03       | firestore-sync             | [Foundation] Firestore sync — backup klaserów/stron/pozycji         | no                    | Czeka na F-02                           |
| S-01       | shop-check-and-mark-bought | Kolekcjoner: status posiadania i oznaczenie kupionego               | no                    | Czeka na F-01 i F-02                    |
| S-02       | binder-management          | Kolekcjoner: tworzenie klaserów i stron                             | no                    | Czeka na F-02 i F-03                    |
| S-03       | cataloging-flow            | Kolekcjoner: katalogowanie kapsla do klasera                        | no                    | Czeka na S-01 i S-02                   |
| S-04       | binder-fill-stats          | Kolekcjoner: zapełnienie klaserów                                   | no                    | Czeka na S-02 i S-03                    |

## Open Roadmap Questions

1. ~~**Czy API crowncaps.info obsługuje autentykację użytkownika?**~~ — **ROZWIĄZANE 2026-06-11**: Laravel Sanctum, tryb cookie. Szczegóły w F-01 "Auth API contract".
2. ~~**Czy API crowncaps.info udostępnia endpoint zapisu do kolekcji?**~~ — **ROZWIĄZANE 2026-06-11**: `POST /data/catalog/caps/{id}/collection` (dodaj) + `DELETE /data/catalog/caps/{id}/collection` (usuń). Fallback niepotrzebny.
3. **Czy crowncaps.info udostępnia API statystyk kolekcji użytkownika (liczba kapsli, podział wg kraju)?** — Owner: deweloper. Block: nie — FR-014 (nice-to-have) może być pominięte bez wpływu na MVP.

## Parked

- **Tryb offline dla sprawdzania isInCollection** — Why parked: PRD §Non-Goals — wymaga aktywnego połączenia z API; widok klaserów (dane lokalne) działa offline. Kandydat do v2.
- **Wersja publiczna** — Why parked: PRD §Non-Goals — osobny projekt, osobny shaping.
- **Rozpoznawanie kapsla ze zdjęcia przez AI** — Why parked: PRD §Non-Goals — PictureSearch pozostaje jako wybór kategorii wizualnych; AI image recognition to oddzielna, kosztowna decyzja.
- **Udostępnianie kolekcji innym użytkownikom** — Why parked: PRD §Non-Goals — aplikacja prywatna, jedno konto.
- **Usuwanie kapsla z kolekcji crowncaps.info z poziomu aplikacji** — Why parked: PRD §Non-Goals — MVP obsługuje tylko dodawanie (oznaczanie jako kupiony).
- **FR-014: Statystyki kolekcji (liczba kapsli, podział wg kraju)** — Why parked: Priority: nice-to-have; dostępność API weryfikowana przed implementacją — endpoint może nie istnieć.

## Done

(Puste przy pierwszej generacji. `/10x-archive` dopisuje wpis tutaj — i przełącza Status na `done` —
gdy zmiana z pasującym `Change ID` zostaje zarchiwizowana. Format:)

- **\<Slice ID\>: \<Outcome\>** — Archived \<YYYY-MM-DD\> → `context/archive/<YYYY-MM-DD-change-id>/`. Lesson: \<pointer do lessons.md lub `—`\>.

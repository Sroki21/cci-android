---
project: "CCI Android — wersja prywatna"
context_type: brownfield
created: 2026-06-10
updated: 2026-06-10
quality_check_status: accepted
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  frs_drafted: 14
  gray_areas_resolved:
    - topic: "kod bazowy"
      decision: "rozbudowujemy cci-android — gotowa integracja API crowncaps.info zostaje, dodajemy warstwę prywatnej kolekcji"
    - topic: "lokalizacja kapsla"
      decision: "hierarchia: klaser (kontynent+nr) → strona (1–15) → pozycja (1–35); kombinacja (klaser, strona, pozycja) unikalna per kapsel"
    - topic: "skala i odbiorcy"
      decision: "wersja prywatna — tylko jeden użytkownik (właściciel kolekcji); wersja publiczna (browser) do osobnego shapingu"
    - topic: "isInCollection"
      decision: "API crowncaps.info zwraca isInCollection per kapsel po zalogowaniu — nie potrzeba lokalnej bazy własności; binder structure przechowywana lokalnie"
    - topic: "kontrola dostępu"
      decision: "logowanie kontem crowncaps.info; jeden użytkownik, brak ról; dane klaserów przechowywane lokalnie na urządzeniu"
  frs_drafted: 0
  quality_check_status: pending
product_type: mobile
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  delivery_weeks: 5
  hard_deadline: null
  after_hours_only: true
---

## Current System Overview

Aplikacja Android (Kotlin + Jetpack Compose, Hilt, Retrofit, Paging 3) będąca przeglą
darką publicznej bazy crowncaps.info. Projekt porzucony przez poprzedniego developera w stanie versionName 1.2 / versionCode 3.

Stack: Kotlin 2.1.20, Jetpack Compose (BOM 2025.05.01), Hilt 2.51.1, Retrofit 2.11, kotlinx.serialization 1.7.3, Paging 3.3.4, Navigation Compose 2.9, Coil 2.7. Architektura MVVM z warstwą Repository. Brak Room (brak lokalnej bazy danych). Brak autentykacji (wszystkie wywołania API są anonimowe).

Istniejące ekrany: HomeScreen (wyszukiwarka + wyniki), CountriesScreen, CountryCapsScreen, LatestCapsScreen, QuickSearchScreen, PictureSearchScreen + PictureSearchCapsScreen, CapDetailScreen.

API crowncaps.info: 7 endpointów, wszystkie działają (HTTP 200). Model `Cap` zawiera pole `isInCollection: Boolean` — zwracane przez API po zalogowaniu; obecna aplikacja nie implementuje logowania, więc pole zawsze wynosi `false`.

Baza referencyjna: crowncaps.info — ponad 366 000 kapsli, 229 krajów, 88 kategorii.

## Problem Statement & Motivation

Kolekcjoner posiada dużą kolekcję kapsli ułożoną fizycznie w klaserach według hierarchii: klaser (kontynent + numer) → strona (1–15 na klaser) → pozycja (1–35 na stronę). Zarządzanie kolekcją wymaga dziś dwóch narzędzi: strony crowncaps.info (baza referencyjna z metadanymi) oraz Excela (fizyczna lokalizacja kapsla w klaserze). Nie istnieje jedna aplikacja, która łączy jedno z drugim.

Ból #1 — w sklepie: kolekcjoner stoi przy półce i nie wie, czy dany kapsel już posiada. Sprawdzenie wymaga pamięci lub dostępu do Excela na laptopie.

Ból #2 — katalogowanie: każdy nowy kapsel wymaga ręcznego przepisania danych z crowncaps.info do Excela i naniesienia fizycznej lokalizacji. Dwa narzędzia, dwa kroki, ryzyko błędu.

Motywacja techniczna: API crowncaps.info już zwraca `isInCollection` per kapsel dla zalogowanego użytkownika — aplikacja nie musi budować własnej bazy własności, wystarczy dodać autentykację i wyeksponować to pole. Warstwa lokalna potrzebna jest wyłącznie dla struktury klaserów, której crowncaps.info nie przechowuje.

## User & Persona

**Persona główna: kolekcjoner kapsli (jeden użytkownik — właściciel aplikacji)**

- Rola: kolekcjoner z dużą kolekcją w klaserach, aktywnie powiększający zbiór
- Kontekst: telefon zawsze przy sobie; kapsle napotyka w sklepach, na targach, u znajomych
- Moment sięgnięcia po aplikację #1: stoi przy półce sklepowej, ma w ręku nieznany kapsel, potrzebuje natychmiastowej odpowiedzi "mam to czy nie?"
- Moment sięgnięcia po aplikację #2: wraca do domu z nowymi kapslami i chce je skatalogować — przypisać do klasera, strony, pozycji — bez otwierania laptopa i Excela

## Access Control

Logowanie przez konto crowncaps.info (mechanizm autentykacji — Bearer token lub sesja — do weryfikacji w dokumentacji API crowncaps.info; crowncaps nie udostępnia publicznej dokumentacji OAuth, wymaga zbadania przed implementacją).

Jeden użytkownik, brak ról, brak mechanizmu udostępniania kolekcji innym. Dane klaserów (struktura lokalna) przechowywane na urządzeniu — nie synchronizowane z crowncaps.info (crowncaps nie ma takiego API). Wersja prywatna nie jest dostępna dla innych użytkowników bez osobnego konta i logowania.

Nie ma zmiany istniejącego modelu dostępu do publicznych endpointów crowncaps.info — te pozostają anonimowe. Zmiana: dodanie warstwy autentykowanej do wywołań zwracających `isInCollection`.

## Success Criteria

### Primary
- Kolekcjoner otwiera aplikację w sklepie, wyszukuje kapsel (tekstem, obrazem lub przez browar), widzi w wynikach które kapsle posiada (isInCollection = true oznaczone wizualnie), tapnięciem oznacza wybrany kapsel jako "kupiony" → aplikacja wywołuje API crowncaps.info aby oznaczyć kapsel w kolekcji + lokalnie kapsel trafia do zakładki "oczekuje na skatalogowanie".
- Kolekcjoner w domu otwiera zakładkę "oczekuje na skatalogowanie", przypisuje kapsel do (klaser, strona, pozycja) — kapsel zmienia status na "skatalogowany".

### Secondary
- Kolekcjoner może zobaczyć statystyki kolekcji: łączna liczba kapsli, podział wg kraju/browaru, zapełnienie klaserów (ile wolnych miejsc na stronie).

### Guardrails
- Slot (klaser, strona, pozycja) jest zawsze unikalny — aplikacja blokuje przypisanie dwóch kapsli do tej samej pozycji.
- Przeglądanie bazy crowncaps.info działa bez logowania — anonimowe endpointy nie są blokowane.
- Błąd wywołania API crowncaps.info (np. brak internetu) nie niszczy stanu lokalnego — dane klaserów pozostają nienaruszone.

## User Stories

### US-01: Sprawdzenie kapsla w sklepie i oznaczenie jako kupiony

- **Given** zalogowany kolekcjoner stoi w sklepie i trzyma nieznany kapsel
- **When** wyszukuje go w aplikacji (tekstem, obrazem lub przeglądając browar) i widzi go w wynikach
- **Then** przy kapslu widzi wyraźny status "posiadam" lub "nie posiadam"; jednym tapnięciem oznacza "kupuję" — kapsel trafia do zakładki "oczekuje na skatalogowanie"

#### Acceptance Criteria
- Status posiadania widoczny bez dodatkowego kroku (nie trzeba wchodzić w szczegóły kapsla)
- Akcja "kupuję" dostępna bezpośrednio z listy wyników
- Po tapnięciu "kupuję" kapsel natychmiast znika z listy "nie posiadam" i pojawia się w zakładce oczekujących
- Jeżeli wywołanie API crowncaps.info nie powiedzie się, użytkownik widzi błąd i kapsel NIE jest oznaczony jako posiadany

### US-02: Katalogowanie w domu

- **Given** zalogowany kolekcjoner ma kapsle w zakładce "oczekuje na skatalogowanie"
- **When** otwiera zakładkę i wybiera kapsel do skatalogowania
- **Then** może przypisać go do (klaser, strona, pozycja); aplikacja blokuje zajęte pozycje; po zapisaniu kapsel znika z listy oczekujących

#### Acceptance Criteria
- Lista oczekujących pokazuje zdjęcie i nazwę kapsla z crowncaps.info
- Zajęte pozycje są wyraźnie oznaczone i nieselektywne
- Pełna strona (35/35) jest oznaczona jako niedostępna

## Functional Requirements

### Autentykacja
- FR-001: Użytkownik może zalogować się kontem crowncaps.info. Priority: must-have. Change: new
  > Socrates: Kontrargument: crowncaps.info może nie mieć publicznego API auth (sesja cookie zamiast Bearer token). Rozwiązanie: FR zostaje; weryfikacja API przed implementacją — jeżeli brak endpointu, otwieramy pytanie otwarte.
- FR-002: Użytkownik może przeglądać bazę crowncaps.info bez logowania. Priority: must-have. Change: preserved
  > Socrates: Brak kontrargumentu — istniejąca funkcjonalność, celowo zachowana.

### Wyszukiwanie i sprawdzanie w sklepie
- FR-003: Zalogowany użytkownik widzi status posiadania (posiadam / nie posiadam) przy każdym kapslu w wynikach i listach; niezalogowany użytkownik nie widzi statusu — zamiast tego wyświetlany jest przycisk logowania. Priority: must-have. Change: new
  > Socrates: Kontrargument: dla niezalogowanego status zawsze false — fałszywe "nie posiadam". Rozwiązanie: ukryj status gdy niezalogowany, pokaż przycisk logowania. FR zaktualizowane.
- FR-004: Użytkownik może wyszukiwać kapsle po tekście. Priority: must-have. Change: preserved
  > Socrates: Brak kontrargumentu — istniejąca funkcjonalność.
- FR-005: Użytkownik może wyszukiwać kapsle po obrazie poprzez wybór kategorii wizualnych. Priority: must-have. Change: preserved
  > Socrates: Kontrargument: istniejący PictureSearch to wybór kategorii, nie rozpoznawanie obrazu AI. Rozwiązanie: zachowaj obecny mechanizm wizualnych kategorii — działa dziś bez AI. Prawdziwe rozpoznawanie obrazu to osobna decyzja na przyszłość.
- FR-006: Użytkownik może przeglądać kapsle według kraju. Priority: must-have. Change: preserved
  > Socrates: Brak kontrargumentu — istniejąca funkcjonalność.

### Akcja w sklepie
- FR-007: Zalogowany użytkownik jednym tapnięciem oznacza kapsel jako "kupiony" — crowncaps.info rejestruje posiadanie (jeżeli API to obsługuje), aplikacja lokalnie dodaje kapsel do zakładki "oczekuje na skatalogowanie". Priority: must-have. Change: new
  > Socrates: Kontrargument: API crowncaps.info może mieć tylko odczyt isInCollection, brak zapisu. Rozwiązanie: FR zostaje; weryfikacja API przed implementacją. Fallback: "kupuję" zapisuje lokalnie i synchronizuje gdy API będzie dostępne.

### Katalogowanie w domu
- FR-008: Użytkownik widzi zakładkę "oczekuje na skatalogowanie" z listą kapsli bez przypisanej pozycji w klaserze. Priority: must-have. Change: new
  > Socrates: Brak kontrargumentu — nowa funkcjonalność odpowiadająca bezpośrednio na ból z screenshotami.
- FR-009: Użytkownik może przypisać kapsel do pozycji (klaser, strona, pozycja) oraz zmienić istniejące przypisanie na inną pozycję; stara pozycja zwalnia się automatycznie. Priority: must-have. Change: new
  > Socrates: Kontrargument: bez edycji pomyłka = usuń i dodaj ponownie. Rozwiązanie: edycja przypisania musi być możliwa — FR zaktualizowane.
- FR-010: Użytkownik może dodać nowy klaser (nazwa: kontynent + numer sekwencyjny) oraz usunąć klaser, który nie zawiera żadnych kapsli. Priority: must-have. Change: new
  > Socrates: Kontrargument: usunięcie klasera z kapslami niszczy dane lokalizacji. Rozwiązanie: usuwanie tylko pustego klasera — aplikacja blokuje usunięcie jeżeli klaser ma kapsle. FR zaktualizowane.
- FR-011: Użytkownik może dodać stronę do klasera (limit 1–15 na klaser); próba dodania 16. strony kończy się czytelnym błędem. Priority: must-have. Change: new
  > Socrates: Brak kontrargumentu — limit domenowy jasno określony.
- FR-012: Aplikacja blokuje przypisanie dwóch kapsli do tej samej pozycji (klaser, strona, pozycja). Priority: must-have. Change: new
  > Socrates: Brak kontrargumentu — unikalność slotu jest wymogiem bezwzględnym.

### Statystyki
- FR-013: Użytkownik widzi zapełnienie klaserów — ile wolnych pozycji na każdej stronie każdego klasera (każda strona ma zawsze 35 pozycji). Priority: must-have. Change: new
  > Socrates: Kontrargument: starsze klasery mogą mieć inną pojemność. Rozwiązanie: 35 pozycji zawsze — wszystkie klasery użytkownika mają taką pojemność. FR zostaje.
- FR-014: Użytkownik widzi statystyki kolekcji z crowncaps.info (liczba kapsli, podział wg kraju). Priority: nice-to-have. Change: new
  > Socrates: Kontrargument: crowncaps.info może nie mieć API statystyk użytkownika. Rozwiązanie: nice-to-have pozostaje; dostępność API weryfikowana przed implementacją.

## Business Logic Changes

Aplikacja określa, które kapsle użytkownik posiada (na podstawie pola `isInCollection` zwracanego przez API crowncaps.info dla zalogowanego użytkownika) i zarządza ich fizyczną lokalizacją w klaserach — gwarantując, że każda pozycja (klaser, strona, pozycja) jest zajęta przez dokładnie jeden kapsel albo wolna.

Reguła domeny działa na dwóch wejściach użytkownika: wyszukanie kapsla (tekstem, kategorią wizualną lub krajem) zwraca wynik z binarnym statusem posiadania; przypisanie kapsla do pozycji klasera rejestruje lokalizację i zwalnia poprzednią jeżeli istniała. Wyjście reguły to jeden z trzech stanów: "nie posiadam" (isInCollection = false), "posiadam, nieskatalogowany" (isInCollection = true, brak lokalnej pozycji), "posiadam, skatalogowany" (isInCollection = true + pozycja w klaserze).

Reguła uzupełniająca klaserów: klaser ma od 1 do 15 stron, każda strona ma dokładnie 35 pozycji. Suma dostępnych pozycji jest znana i stała per klaser — zapełnienie jest obliczalne lokalnie bez wywołań API.

## Constraints & Compatibility

- API crowncaps.info: integracja z 7 istniejącymi endpointami musi pozostać nienaruszona. Anonimowe endpointy (przeglądanie, wyszukiwanie) działają bez zmian; dodajemy warstwę autentykowaną do wywołań wymagających isInCollection.
- Brak migracji danych: użytkownik nie ma istniejących danych lokalnych w aplikacji — Room database jest nowa, brak backfill. Dane na crowncaps.info (kolekcja użytkownika) nie są modyfikowane przez migrację.
- Backward compatibility: istniejące ekrany przeglądania (CountriesScreen, LatestCapsScreen, QuickSearchScreen, PictureSearchScreen, CapDetailScreen) muszą działać bez regresji dla niezalogowanego użytkownika.
- Mechanizm auth crowncaps.info: nieznany (brak publicznej dokumentacji API) — wymaga zbadania przed implementacją. To jedyne zewnętrzne ryzyko blokujące.

## Non-Functional Requirements

- Wyniki wyszukiwania pojawiają się tak szybko jak to możliwe; kilka sekund oczekiwania jest akceptowalne jeżeli wynik jest pewny i zapobiega zakupowi duplikatu. Trafność wyniku ważniejsza niż jego szybkość.
- Dane struktury klaserów (lokalizacje kapsli) są przechowywane wyłącznie na urządzeniu użytkownika i nie opuszczają go w żadnej formie (brak synchronizacji z chmurą, brak backupu zewnętrznego w v1).
- Aplikacja działa na Androidzie 7.0+ (API 24) — istniejące minSdk bez zmian.
- Błąd sieciowy (brak internetu, timeout) nie powoduje utraty danych lokalnych ani zawieszenia aplikacji — użytkownik widzi komunikat błędu, dane klaserów pozostają nienaruszone.

## Non-Goals

- **Tryb offline dla sprawdzania isInCollection** — celowo pominięty w v1. isInCollection wymaga aktywnego połączenia z API crowncaps.info. Widok klaserów (dane lokalne) działa offline. Offline dla kolekcji — v2.
- **Wersja publiczna (browser dla wszystkich)** — osobny projekt, osobny shaping. Ta wersja to wyłącznie prywatna, jednoużytkownikowa aplikacja.
- **Prawdziwe rozpoznawanie obrazu AI** — PictureSearch pozostaje jako wybór kategorii wizualnych. Identyfikacja kapsla ze zdjęcia przez model AI to osobna, kosztowna decyzja — nie MVP.
- **Synchronizacja struktury klaserów z chmurą / backup** — dane klaserów są lokalne. Brak synchronizacji między urządzeniami i brak zdalnego backupu w v1.
- **Udostępnianie kolekcji innym użytkownikom** — aplikacja prywatna, jedno konto, brak funkcji społecznościowych.
- **Usuwanie kapsla z kolekcji crowncaps.info z poziomu aplikacji** — MVP obsługuje tylko dodawanie (oznaczanie jako kupiony); usuwanie z kolekcji crowncaps to operacja poza zakresem.

## Open Questions

1. **Czy API crowncaps.info obsługuje autentykację przez token (Bearer)?** — Owner: deweloper. Weryfikacja przed implementacją FR-001. Block: tak — bez auth brak isInCollection i brak FR-007.
2. **Czy API crowncaps.info udostępnia endpoint zapisu do kolekcji (POST/PUT)?** — Owner: deweloper. Weryfikacja przed implementacją FR-007. Fallback: lokalny stan + późniejsza synchronizacja jeżeli API niedostępne.
3. **Czy crowncaps.info udostępnia API statystyk użytkownika (liczba kapsli, podział wg kraju)?** — Owner: deweloper. Weryfikacja przed implementacją FR-014 (nice-to-have). Block: nie — FR-014 można pominąć.

## Timeline acknowledgment

Acknowledged on 2026-06-10: 5-week delivery requires sustained dedication; user accepted.

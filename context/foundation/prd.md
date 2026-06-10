---
project: "CCI Android — wersja prywatna"
version: 1
status: draft
created: 2026-06-10
context_type: brownfield
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

Aplikacja Android (Kotlin 2.1.20, Jetpack Compose BOM 2025.05.01, Hilt 2.51.1, Retrofit 2.11, kotlinx.serialization 1.7.3, Paging 3.3.4, Navigation Compose 2.9, Coil 2.7) będąca przeglądarką publicznej bazy crowncaps.info. Projekt porzucony przez poprzedniego developera w stanie versionName 1.2 / versionCode 3. Architektura MVVM z warstwą Repository. Aplikacja nie posiada lokalnej bazy danych ani warstwy autentykacji — wszystkie wywołania API są anonimowe.

Istniejące ekrany: HomeScreen (wyszukiwarka + wyniki), CountriesScreen, CountryCapsScreen, LatestCapsScreen, QuickSearchScreen, PictureSearchScreen + PictureSearchCapsScreen, CapDetailScreen.

API crowncaps.info: 7 endpointów, wszystkie działają (HTTP 200). Model `Cap` zawiera pole `isInCollection` — zwracane przez API po zalogowaniu; obecna aplikacja nie implementuje logowania, więc pole zawsze wynosi `false`. Baza referencyjna: crowncaps.info — ponad 366 000 kapsli, 229 krajów, 88 kategorii.

## Problem Statement & Motivation

Kolekcjoner posiada dużą kolekcję kapsli ułożoną fizycznie w klaserach według hierarchii: klaser (kontynent + numer) → strona (1–15 na klaser) → pozycja (1–35 na stronę). Zarządzanie kolekcją wymaga dziś dwóch narzędzi: strony crowncaps.info (baza referencyjna z metadanymi) oraz Excela (fizyczna lokalizacja kapsla w klaserze). Nie istnieje jedna aplikacja, która łączy jedno z drugim.

Ból #1 — w sklepie: kolekcjoner stoi przy półce i nie wie, czy dany kapsel już posiada. Sprawdzenie wymaga pamięci lub dostępu do Excela na laptopie.

Ból #2 — katalogowanie: każdy nowy kapsel wymaga ręcznego przepisania danych z crowncaps.info do Excela i naniesienia fizycznej lokalizacji. Dwa narzędzia, dwa kroki, ryzyko błędu. Stan pośredni ("kupione, ale jeszcze nie w klaserze — bo piwo niewypiłem") nie istnieje w żadnym z narzędzi — kolekcjoner robi screenshots jako substytut.

Motywacja: API crowncaps.info już zwraca `isInCollection` per kapsel dla zalogowanego użytkownika — aplikacja nie musi budować własnej bazy własności, wystarczy dodać autentykację i wyeksponować to pole. Warstwa lokalna potrzebna jest wyłącznie dla struktury klaserów, której crowncaps.info nie przechowuje.

## User & Persona

**Persona główna: kolekcjoner kapsli (jeden użytkownik — właściciel aplikacji)**

- Rola: kolekcjoner z dużą kolekcją w klaserach, aktywnie powiększający zbiór; zbiera wyłącznie kapsle z piw, które sam wypił
- Kontekst: telefon zawsze przy sobie; kapsle napotyka w sklepach, na targach, u znajomych
- Moment sięgnięcia po aplikację #1: stoi przy półce sklepowej, trzyma nieznany kapsel, potrzebuje natychmiastowej odpowiedzi "mam to czy nie?" przed zakupem
- Moment sięgnięcia po aplikację #2: wraca do domu z nowymi kapslami i chce je skatalogować — przypisać do klasera, strony, pozycji — bez otwierania laptopa i Excela

## Success Criteria

### Primary
- Kolekcjoner otwiera aplikację w sklepie, wyszukuje kapsel (tekstem, obrazem lub przez browar), widzi w wynikach które kapsle posiada (oznaczone wizualnie), tapnięciem oznacza wybrany kapsel jako "kupiony" → aplikacja rejestruje posiadanie w crowncaps.info + lokalnie kapsel trafia do zakładki "oczekuje na skatalogowanie".
- Kolekcjoner w domu otwiera zakładkę "oczekuje na skatalogowanie", przypisuje kapsel do (klaser, strona, pozycja) — kapsel zmienia status na "skatalogowany".

### Secondary
- Kolekcjoner może zobaczyć zapełnienie klaserów: ile wolnych pozycji pozostało na każdej stronie każdego klasera.

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
- Po tapnięciu "kupuję" kapsel pojawia się w zakładce oczekujących
- Jeżeli rejestracja w crowncaps.info nie powiedzie się, użytkownik widzi błąd i kapsel NIE jest oznaczony jako posiadany

### US-02: Katalogowanie w domu

- **Given** zalogowany kolekcjoner ma kapsle w zakładce "oczekuje na skatalogowanie"
- **When** otwiera zakładkę i wybiera kapsel do skatalogowania
- **Then** może przypisać go do (klaser, strona, pozycja); aplikacja blokuje zajęte pozycje; po zapisaniu kapsel znika z listy oczekujących

#### Acceptance Criteria
- Lista oczekujących pokazuje zdjęcie i nazwę kapsla z crowncaps.info
- Zajęte pozycje są wyraźnie oznaczone i nieselektywne
- Pełna strona (35/35) jest oznaczona jako niedostępna

## Scope of Change

### Nowe możliwości

- [new] FR-001: Użytkownik może zalogować się kontem crowncaps.info.
  > Socrates: Kontrargument: crowncaps.info może nie mieć publicznego API auth. Rozwiązanie: FR zostaje; weryfikacja API przed implementacją — jeżeli brak endpointu, otwieramy pytanie otwarte.
- [new] FR-003: Zalogowany użytkownik widzi status posiadania (posiadam / nie posiadam) przy każdym kapslu w wynikach i listach; niezalogowany użytkownik nie widzi statusu — zamiast tego wyświetlany jest przycisk logowania.
  > Socrates: Kontrargument: dla niezalogowanego status zawsze fałszywy. Rozwiązanie: ukryj status gdy niezalogowany, pokaż przycisk logowania. FR zaktualizowane.
- [new] FR-007: Zalogowany użytkownik jednym tapnięciem oznacza kapsel jako "kupiony" — crowncaps.info rejestruje posiadanie (jeżeli API to obsługuje), aplikacja lokalnie dodaje kapsel do zakładki "oczekuje na skatalogowanie".
  > Socrates: Kontrargument: API crowncaps.info może mieć tylko odczyt, brak zapisu. Rozwiązanie: FR zostaje; weryfikacja API przed implementacją. Fallback: "kupuję" zapisuje lokalnie i synchronizuje gdy API będzie dostępne.
- [new] FR-008: Użytkownik widzi zakładkę "oczekuje na skatalogowanie" z listą kapsli bez przypisanej pozycji w klaserze.
  > Socrates: Brak kontrargumentu — nowa funkcjonalność odpowiadająca bezpośrednio na ból z screenshotami.
- [new] FR-009: Użytkownik może przypisać kapsel do pozycji (klaser, strona, pozycja) oraz zmienić istniejące przypisanie na inną pozycję; stara pozycja zwalnia się automatycznie.
  > Socrates: Kontrargument: bez edycji pomyłka = usuń i dodaj ponownie. Rozwiązanie: edycja przypisania musi być możliwa — FR zaktualizowane.
- [new] FR-010: Użytkownik może dodać nowy klaser (nazwa: kontynent + numer sekwencyjny) oraz usunąć klaser, który nie zawiera żadnych kapsli.
  > Socrates: Kontrargument: usunięcie klasera z kapslami niszczy dane lokalizacji. Rozwiązanie: usuwanie tylko pustego klasera — aplikacja blokuje usunięcie jeżeli klaser ma kapsle. FR zaktualizowane.
- [new] FR-011: Użytkownik może dodać stronę do klasera (limit 1–15 na klaser); próba dodania 16. strony kończy się czytelnym komunikatem o błędzie.
  > Socrates: Brak kontrargumentu — limit domenowy jasno określony.
- [new] FR-012: Aplikacja blokuje przypisanie dwóch kapsli do tej samej pozycji (klaser, strona, pozycja).
  > Socrates: Brak kontrargumentu — unikalność slotu jest wymogiem bezwzględnym.
- [new] FR-013: Użytkownik widzi zapełnienie klaserów — ile wolnych pozycji na każdej stronie każdego klasera (każda strona ma zawsze 35 pozycji).
  > Socrates: Kontrargument: starsze klasery mogą mieć inną pojemność. Rozwiązanie: 35 pozycji zawsze — wszystkie klasery użytkownika mają taką pojemność. FR zostaje.
- [new] FR-014: Użytkownik widzi statystyki kolekcji z crowncaps.info (liczba kapsli, podział wg kraju). Priority: nice-to-have.
  > Socrates: Kontrargument: crowncaps.info może nie mieć API statystyk użytkownika. Rozwiązanie: nice-to-have pozostaje; dostępność API weryfikowana przed implementacją.

### Zachowane bez zmian

- [preserved] FR-002: Użytkownik może przeglądać bazę crowncaps.info bez logowania.
  > Socrates: Brak kontrargumentu — istniejąca funkcjonalność, celowo zachowana.
- [preserved] FR-004: Użytkownik może wyszukiwać kapsle po tekście.
  > Socrates: Brak kontrargumentu — istniejąca funkcjonalność.
- [preserved] FR-005: Użytkownik może wyszukiwać kapsle po obrazie poprzez wybór kategorii wizualnych.
  > Socrates: Kontrargument: istniejący PictureSearch to wybór kategorii, nie rozpoznawanie obrazu AI. Rozwiązanie: zachowaj obecny mechanizm — działa dziś bez AI. Prawdziwe rozpoznawanie obrazu to osobna decyzja na przyszłość.
- [preserved] FR-006: Użytkownik może przeglądać kapsle według kraju.
  > Socrates: Brak kontrargumentu — istniejąca funkcjonalność.

## Constraints & Compatibility

- **Integracja API crowncaps.info**: integracja z 7 istniejącymi endpointami musi pozostać nienaruszona. Anonimowe endpointy (przeglądanie, wyszukiwanie) działają bez zmian; warstwa autentykowana jest dodawana wyłącznie do wywołań wymagających danych użytkownika.
- **Brak migracji danych**: aplikacja nie ma istniejącej lokalnej bazy danych — brak danych do migracji. Dane na crowncaps.info (kolekcja użytkownika) nie są modyfikowane przez tę zmianę.
- **Backward compatibility ekranów**: istniejące ekrany przeglądania (CountriesScreen, LatestCapsScreen, QuickSearchScreen, PictureSearchScreen, CapDetailScreen) muszą działać bez regresji dla niezalogowanego użytkownika.
- **Mechanizm autentykacji crowncaps.info**: nieznany (brak publicznej dokumentacji API) — wymaga zbadania przed implementacją. To jedyne zewnętrzne ryzyko blokujące; patrz Open Questions #1.

## Business Logic Changes

Zmiana dodaje nową regułę domenową: aplikacja określa, które kapsle użytkownik posiada (na podstawie pola `isInCollection` zwracanego przez API crowncaps.info dla zalogowanego użytkownika) i zarządza ich fizyczną lokalizacją w klaserach — gwarantując, że każda pozycja (klaser, strona, pozycja) jest zajęta przez dokładnie jeden kapsel albo wolna.

Reguła domeny działa na dwóch wejściach użytkownika: wyszukanie kapsla (tekstem, kategorią wizualną lub krajem) zwraca wynik z binarnym statusem posiadania; przypisanie kapsla do pozycji klasera rejestruje lokalizację i zwalnia poprzednią jeżeli istniała. Wyjście reguły to jeden z trzech stanów: "nie posiadam" (isInCollection = false), "posiadam, nieskatalogowany" (isInCollection = true, brak lokalnej pozycji), "posiadam, skatalogowany" (isInCollection = true + pozycja w klaserze).

Reguła uzupełniająca klaserów: klaser ma od 1 do 15 stron, każda strona ma dokładnie 35 pozycji. Suma dostępnych pozycji jest znana i stała per klaser — zapełnienie jest obliczalne lokalnie bez wywołań API.

Istniejąca logika przeglądania bazy crowncaps.info pozostaje bez zmian.

## Access Control Changes

Zmiana dodaje autentykację przez konto crowncaps.info. Jeden użytkownik, brak ról, brak mechanizmu udostępniania kolekcji innym. Dane struktury klaserów przechowywane wyłącznie na urządzeniu — nie synchronizowane z crowncaps.info.

Anonimowe endpointy crowncaps.info (przeglądanie, wyszukiwanie, szczegóły kapsla) pozostają dostępne bez logowania — brak zmian w modelu dostępu dla niezalogowanego użytkownika.

Mechanizm autentykacji crowncaps.info (token czy sesja) wymaga weryfikacji przed implementacją; patrz Open Questions #1.

## Non-Goals

- **Tryb offline dla sprawdzania posiadanych kapsli** — celowo pominięty w v1. Weryfikacja posiadania wymaga aktywnego połączenia z API crowncaps.info. Widok klaserów (dane lokalne) działa offline. Offline dla kolekcji — kandydat do v2.
- **Wersja publiczna (browser dla wszystkich)** — osobny projekt, osobny shaping. Ta wersja to wyłącznie prywatna, jednoużytkownikowa aplikacja.
- **Rozpoznawanie kapsla ze zdjęcia przez AI** — PictureSearch pozostaje jako wybór kategorii wizualnych. Identyfikacja kapsla ze zdjęcia przez model rozpoznawania obrazu to osobna, kosztowna decyzja — nie należy do tej zmiany.
- **Synchronizacja struktury klaserów z zewnętrznym serwisem** — dane klaserów są lokalne. Brak synchronizacji między urządzeniami i brak zdalnego backupu w v1.
- **Udostępnianie kolekcji innym użytkownikom** — aplikacja prywatna, jedno konto, brak funkcji społecznościowych.
- **Usuwanie kapsla z kolekcji crowncaps.info** — ta zmiana obsługuje tylko oznaczanie jako posiadany; usuwanie z kolekcji crowncaps jest poza zakresem.

## Open Questions

1. **Czy API crowncaps.info obsługuje autentykację użytkownika (jaki mechanizm: token, sesja cookiowa, inne)?** — Owner: deweloper. Weryfikacja przed implementacją FR-001. Block: tak — bez autentykacji brak statusu posiadania i brak FR-007.
2. **Czy API crowncaps.info udostępnia endpoint zapisu do kolekcji użytkownika?** — Owner: deweloper. Weryfikacja przed implementacją FR-007. Fallback: akcja "kupuję" zapisuje lokalny stan; synchronizacja z crowncaps gdy endpoint będzie dostępny lub zidentyfikowany.
3. **Czy crowncaps.info udostępnia API statystyk kolekcji użytkownika (liczba kapsli, podział wg kraju)?** — Owner: deweloper. Weryfikacja przed implementacją FR-014 (nice-to-have). Block: nie — FR-014 można pominąć bez wpływu na MVP.

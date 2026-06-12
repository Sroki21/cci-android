---
id: advanced-search
status: planned
created: 2026-06-12
---

# Advanced Search — change identity

## Problem

Przycisk „Szukanie zaawansowane" na HomeScreen jest wyłączony (disabled placeholder).
Jedyne dostępne wyszukiwanie to `QuickSearch` — wolny tekst, jedno pole, brak filtrów.

## Solution

Nowy ekran `AdvancedSearchScreen` dostępny z HomeScreen z formularzem filtrów:
- **ID** — operator: Zawiera / Równe / Zaczyna się od
- **Tekst** — operator: Zawiera / Równe / Zaczyna się od
- **Kraj** — autocomplete z listą krajów z API (operator: Równe)
- **Producent** — operator: Zawiera / Równe / Zaczyna się od

Wyniki wyświetlane na tym samym ekranie co formularz (poniżej), paginowane (Paging 3),
identycznie stylizowane jak wyniki QuickSearch.

## Scope

### In
- Nowy ekran `AdvancedSearchScreen` + `AdvancedSearchViewModel`
- Model `AdvancedSearchFilter` + enum `SearchOperator`
- Nowy endpoint Retrofit w `CapApiService` z parametrami filtrowania
- Nowy `AdvancedSearchPagingSource` w `CapsRepository`
- Ładowanie listy krajów (reuse `CountriesRepository.getCountries()`)
- `Screen.AdvancedSearch` w `navigation/Screen.kt`
- Route w `MainActivity.kt` NavHost
- Odblokowanie przycisku na `HomeScreen`

### Out
- Filtr kategorii (nie jest wymagany)
- Filtr statusu kolekcji (nie jest wymagany)
- Sortowanie wyników
- Historia wyszukiwań

## API Risk

Obecny endpoint `GET api/v1/caps?query=` obsługuje tylko wolny tekst.
Nieznane: czy API przyjmuje parametry `country_id`, `producer`, a także tryb operatora (exact/prefix).
Plan zakłada optymistyczne dodanie tych parametrów i weryfikację po deploymencie.
Dla ID z operatorem Równe — fallback na `GET api/v1/caps/{id}`.

# Home Screen Redesign — Implementation Plan

## Overview

Przebudowa ekranu głównego: stałe pole Szukaj na górze zawartości, TopAppBar z nazwą aplikacji i stanem sesji (Zaloguj / email użytkownika), cztery przyciski nawigacyjne (dwa aktywne, dwa disabled jako placeholdery na przyszłe ekrany). Usunięcie trzech starych przycisków (Picture search, Additions, Countries).

## Current State Analysis

- `HomeScreen.kt` — Scaffold z TopAppBar zawierającym tytuł + ikonę wyszukiwania (toggle na `searchVisible`); 4 `NavigationItem` w Column: Picture search, Additions, Countries, Klasery.
- `SearchBar.kt` — komponent paska w TopAppBar (BasicTextField z przyciskiem back); nie nadaje się do reużycia w obszarze treści bez modyfikacji.
- `SessionRepository` — `@Singleton` z `isLoggedIn: StateFlow<Boolean>` i `setLoggedIn(Boolean)`; brak pola userName.
- `AuthRepository` — przy `login()` zapisuje `setLoggedIn(true)`, przy `logout()` zeruje; email nigdzie nie jest przechowywany.
- `QuickSearchScreen` / `PictureSearchScreen` — oba istnieją i są podłączone w NavGraph; tylko przyciski na HomeScreen zostaną zmienione.
- `NavigationItem` — `ListItem` z ikoną, tekstem i `Modifier.clickable`; bez parametru `enabled`.
- `MainActivity.kt` — `HomeScreen(onClick = { navController.navigate(it.route) }, onSearch = { navController.navigate(Screen.QuickSearchResults.createUrl(it)) })`.

## Desired End State

HomeScreen wyświetla:
1. TopAppBar: „Crowncaps.Info" po lewej; po prawej `TextButton("Zaloguj")` gdy nie zalogowany → `Screen.Login`, lub email użytkownika jako `Text` gdy zalogowany.
2. W obszarze treści (pod TopAppBar): `OutlinedTextField` z etykietą „Szukaj" i ikoną wysyłania; zatwierdzenie (Enter lub ikona) → nawigacja do `QuickSearchResults`.
3. Cztery `NavigationItem` w kolejności:
   - Szukaj wg zdjęcia (aktywny → `Screen.PictureSearch`)
   - Szukanie zaawansowane (disabled — placeholder na S-04)
   - Statystyki (disabled — placeholder na przyszłość)
   - Klasery (aktywny → `Screen.Binders`)

### Key Discoveries

- `SessionRepository` (`data/SessionRepository.kt`) jest `@Singleton` i jest już wstrzykiwany do `AuthRepository` — naturalne miejsce na dodanie `userName`.
- `AuthRepository.login()` otrzymuje `email: String` jako parametr → wystarczy zapisać go w `SessionRepository` przy `200`.
- `HomeViewModel` będzie pierwszym ViewModel dla HomeScreen; Hilt wstrzyknie `SessionRepository`.
- `NavigationItem` zdefiniowany w `MainActivity.kt:189` — dodanie `enabled` w tym miejscu.

## What We're NOT Doing

- Implementacja ekranu Szukanie zaawansowane (S-04).
- Implementacja ekranu Statystyki (osobna zmiana).
- Fix `startDestination` → ekran logowania przy starcie (odłożony).
- Usunięcie nieużywanych ekranów Countries / Latest z NavGraph (zostawiamy kod API na przyszłość).
- Funkcja wylogowania z TopAppBar (email jest wyświetlany jako tekst, bez akcji na tym etapie).
- Instant search / debounce.

## Implementation Approach

Jedna faza obejmuje cały stack od warstwy danych po UI:
1. `SessionRepository` + `AuthRepository` — dodanie userName.
2. `HomeViewModel` — nowy ViewModel eksponujący `HomeUiState`.
3. `NavigationItem` — rozszerzenie o parametr `enabled`.
4. `HomeScreen.kt` — pełny redesign layoutu.
5. `MainActivity.kt` — dodanie callbacku `onLoginClick`.

---

## Phase 1: Home Screen — pełny redesign

### Overview

Jeden pass przez całą pionową warstwę: dane → ViewModel → UI → wiring. Po tej fazie HomeScreen wygląda i zachowuje się zgodnie z wymaganiami.

### Changes Required

#### 1. SessionRepository — dodanie userName

**File**: `app/src/main/java/pl/sroki/cci/android/data/SessionRepository.kt`

**Intent**: Przechować email zalogowanego użytkownika, żeby TopAppBar mógł go wyświetlić bez dodatkowego żądania sieciowego.

**Contract**: Dodać `private val _userName = MutableStateFlow<String?>(null)`, `val userName: StateFlow<String?> = _userName.asStateFlow()` oraz `fun setUserName(value: String?)`. Wzorzec identyczny jak istniejące `_isLoggedIn` / `setLoggedIn`.

---

#### 2. AuthRepository — zapis emaila przy logowaniu/wylogowaniu

**File**: `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt`

**Intent**: Uzupełnić istniejącą logikę sukcesu i wylogowania o zapis/reset emaila.

**Contract**: W bloku `200 ->` wywołać `sessionRepository.setUserName(email)` po `sessionRepository.setLoggedIn(true)`. W metodzie `logout()` wywołać `sessionRepository.setUserName(null)` po `setLoggedIn(false)`.

---

#### 3. HomeViewModel — nowy plik

**File**: `app/src/main/java/pl/sroki/cci/android/ui/home/HomeViewModel.kt`

**Intent**: Dostarczyć HomeScreen stan autentykacji bez bezpośredniego dostępu do SessionRepository z warstwy UI.

**Contract**: `data class HomeUiState(val isLoggedIn: Boolean = false, val userName: String? = null)`. `@HiltViewModel class HomeViewModel @Inject constructor(private val sessionRepository: SessionRepository) : ViewModel()` z `val uiState: StateFlow<HomeUiState>` wyprodukowanym przez `combine(sessionRepository.isLoggedIn, sessionRepository.userName)` i mapowanym do `HomeUiState`.

---

#### 4. NavigationItem — parametr enabled

**File**: `app/src/main/java/pl/sroki/cci/android/MainActivity.kt` (linia ~189)

**Intent**: Umożliwić wyświetlanie nieaktywnych przycisków nawigacyjnych bez dotykania logiki wywołującego.

**Contract**: Dodać `enabled: Boolean = true` do sygnatury. Zastąpić `Modifier.clickable(onClick = onClick)` przez `Modifier.clickable(enabled = enabled, onClick = onClick)`. Zastosować `alpha(if (enabled) 1f else ContentAlpha.disabled)` lub Material3 `LocalContentColor` na `Text`/`Icon` by uzyskać wizualne przyciemnienie.

---

#### 5. HomeScreen.kt — pełny redesign

**File**: `app/src/main/java/pl/sroki/cci/android/ui/home/HomeScreen.kt`

**Intent**: Zastąpić obecny layout (search-toggle w TopAppBar + 4 stare przyciski) nowym layoutem: stałe pole Szukaj + 4 przyciski w ustalonej kolejności z auth state w TopAppBar.

**Contract**:
- Sygnatura: `fun HomeScreen(onClick: (Screen) -> Unit = {}, onSearch: (String) -> Unit = {}, onLoginClick: () -> Unit = {})` — zachować `onClick` i `onSearch`, dodać `onLoginClick`.
- Usunąć `var searchVisible` i cały związany z nim kod.
- Pobrać ViewModel: `val vm = hiltViewModel<HomeViewModel>()`, `val uiState by vm.uiState.collectAsState()`.
- TopAppBar `actions`: jeśli `uiState.isLoggedIn` → `Text(uiState.userName ?: "")` (nieklikalny), inaczej → `TextButton(onClick = onLoginClick) { Text("Zaloguj") }`.
- Treść Column: `OutlinedTextField` dla wyszukiwania (lokalne `var query` state, `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)`, `keyboardActions = KeyboardActions(onSearch = { onSearch(query) })`, `trailingIcon` = ikona Search wyzwalająca `onSearch(query)`), następnie 4 `NavigationItem`:
  - `NavigationItem("Szukaj wg zdjęcia", Icons.Filled.CameraAlt) { onClick(Screen.PictureSearch) }`
  - `NavigationItem("Szukanie zaawansowane", Icons.Filled.FilterList, enabled = false) {}`
  - `NavigationItem("Statystyki", Icons.Filled.BarChart, enabled = false) {}`
  - `NavigationItem("Klasery", Icons.Filled.FolderOpen) { onClick(Screen.Binders) }`

---

#### 6. MainActivity.kt — dodanie onLoginClick

**File**: `app/src/main/java/pl/sroki/cci/android/MainActivity.kt`

**Intent**: Podpiąć nowy callback nawigacyjny do ekranu logowania.

**Contract**: W `composable(Screen.Home.route)` dodać `onLoginClick = { navController.navigate(Screen.Login.route) }` do wywołania `HomeScreen`.

---

### Success Criteria

#### Automated Verification

- Kompilacja bez błędów: `./gradlew :app:compileDebugKotlin`
- Testy jednostkowe przechodzą: `./gradlew :app:testDebugUnitTest`

#### Manual Verification

- HomeScreen pokazuje OutlinedTextField „Szukaj" na górze, pod nim 4 przyciski
- Wpisanie frazy + Enter nawiguje do listy wyników QuickSearch
- Tap „Szukaj wg zdjęcia" → otwiera ekran wyszukiwania zdjęciem
- Tap „Szukanie zaawansowane" i „Statystyki" — przyciski są widoczne ale nieaktywne (brak nawigacji, wizualnie przyciemnione)
- Tap „Klasery" → otwiera ekran klaserów
- Niezalogowany: TopAppBar pokazuje `TextButton("Zaloguj")`; tap → ekran logowania
- Po zalogowaniu: TopAppBar pokazuje email użytkownika zamiast przycisku
- Brak regresji w QuickSearch, PictureSearch, BindersScreen

**Implementation Note**: Po przejściu Automated, pauza na manual gate przed commitem.

---

## Testing Strategy

### Unit Tests

- `HomeViewModel` — stan `isLoggedIn=false/userName=null` gdy `SessionRepository` emituje `false/null`; stan po emisji `true/"email@example.com"` z SessionRepository.

### Manual Testing Steps

1. Uruchom aplikację — sprawdź layout HomeScreen
2. Wpisz frazę w polu Szukaj, naciśnij Enter — wyniki powinny się pojawić
3. Dotknij ikony search w polu — identyczny efekt jak Enter
4. Sprawdź przyciśnięty stan „Szukanie zaawansowane" i „Statystyki" (szare, bez ripple)
5. Zaloguj się przez ekran Login — sprawdź czy email pojawia się w TopAppBar

## References

- `app/src/main/java/pl/sroki/cci/android/ui/home/HomeScreen.kt`
- `app/src/main/java/pl/sroki/cci/android/data/SessionRepository.kt`
- `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt`
- `app/src/main/java/pl/sroki/cci/android/MainActivity.kt`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Home Screen — pełny redesign

#### Automated

- [x] 1.1 Kompilacja bez błędów: `./gradlew :app:compileDebugKotlin`
- [x] 1.2 Testy jednostkowe przechodzą: `./gradlew :app:testDebugUnitTest`

#### Manual

- [ ] 1.3 OutlinedTextField „Szukaj" widoczny na górze, 4 przyciski poniżej
- [ ] 1.4 Enter w polu Szukaj nawiguje do wyników QuickSearch
- [ ] 1.5 Szukaj wg zdjęcia → otwiera PictureSearch
- [ ] 1.6 Szukanie zaawansowane i Statystyki — widoczne, nieaktywne
- [ ] 1.7 Klasery → otwiera BindersScreen
- [ ] 1.8 Niezalogowany → TopAppBar z „Zaloguj"; zalogowany → email
- [ ] 1.9 Brak regresji w innych ekranach

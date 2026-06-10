## Architektura projektu — CCI Android

### Struktura pakietów

Korzeń: `app/src/main/java/pl/sroki/cci/android/`

```
data/
  datasource/remote/    ← Interfejsy Retrofit (CapApiService, CountryApiService, CategoryApiService)
  model/                ← Modele używane TYLKO w warstwie danych (np. Country)
  *Repository.kt        ← Repozytoria per domena (CapsRepository, CountriesRepository, ...)
  *PagingSource.kt      ← Implementacje PagingSource<Int, T> tworzone przez repozytoria
di/                     ← Moduły Hilt (@Module @InstallIn), nazwane <Domain>Module.kt
model/                  ← Modele domenowe współdzielone przez UI i dane (Cap, CapExtended, ...)
                          oznaczone @Serializable jeśli serializowane z/do API
navigation/             ← Definicje tras (Screen.kt — sealed class)
ui/
  <feature>/            ← Jeden folder per funkcja (np. catalog/latest, home)
    <Feature>Screen.kt  ← Composable entry point ekranu
    <Feature>ViewModel.kt ← @HiltViewModel dla ekranu
    <Feature>*View.kt   ← Composable pod-komponenty ekranu
  components/           ← Współdzielone, reużywalne Composable
  theme/                ← Color.kt, Type.kt, Shape.kt, Theme.kt
CCIApplication.kt       ← @HiltAndroidApp
MainActivity.kt         ← Single-activity z NavHost
```

### Reguła podziału modeli

- `model/` — modele domenowe używane przez wiele ekranów lub warstwę UI. Nowe modele API trafiają tutaj z adnotacją `@Serializable`.
- `data/model/` — modele ściśle wewnętrzne dla warstwy danych (nie eksponowane do UI).

### MVVM + Repository — granice odpowiedzialności

- **Composable** — tylko renderowanie i propagacja eventów; zero logiki biznesowej.
- **ViewModel** — stan UI jako `StateFlow`/`Flow`, eksponowany przez `collectAsState()`; wywołuje Repository; nie zna nic o Retrofit ani bazie danych.
- **Repository** — koordynuje źródła danych (remote API, lokalna baza); nie zna nic o UI.
- **PagingSource** — implementuje `PagingSource<Int, T>`; tworzony przez Repository, nie bezpośrednio przez ViewModel.

### Hilt DI — wzorzec wstrzykiwania

- Moduły w `di/`, nazwane `<Domain>Module.kt`.
- API serwisy jako `@Singleton` z `NetworkModule` — jeden Retrofit client dla całej aplikacji.
- Repozytoria wstrzykiwane przez konstruktor (`@Inject constructor`) do ViewModeli.
- Nowy ViewModel: `@HiltViewModel class <Feature>ViewModel @Inject constructor(private val repo: <Domain>Repository) : ViewModel()`.

### Nawigacja

- Trasy zdefiniowane w `navigation/Screen.kt` jako `sealed class Screen(val route: String)`.
- NavHost w `MainActivity.kt` — nowe trasy dodawaj tam (`composable(Screen.X.route) { ... }`).
- Nawigacja między ekranami: `navController.navigate(Screen.X.route)`.
- Jeśli trasa wymaga parametrów: dodaj je do `Screen` jako `{param}` w route i odczytuj przez `backStackEntry.arguments`.

### Testowanie

Testy jednostkowe (host JVM): `app/src/test/java/pl/sroki/cci/android/`
- Testuj ViewModel i Repository logic; nie Composables.
- Używaj `io.mockk:mockk` do mockowania zależności przez constructor injection.
- Używaj `kotlinx-coroutines-test` (`runTest`) dla suspend functions i Flow.
- Wzorzec pliku: `<TestedClass>Test.kt` w odpowiadającym pakiecie źródłowym.

Testy instrumentowane (Android device/emulator): `app/src/androidTest/java/pl/sroki/cci/android/`
- Używaj `ComposeTestRule` dla testów UI Compose.
- Preferuj `onNodeWithText()`/`onNodeWithContentDescription()` nad Espresso dla widoków Compose.

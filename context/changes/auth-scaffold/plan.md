# Auth Scaffold — Plan implementacji

## Overview

Dodanie fundamentu autentykacji do aplikacji CCI Android: OkHttp `CookieJar` z persystencją
(biblioteka `PersistentCookieJar`), interceptor CSRF, `AuthRepository` (login/logout),
`SessionRepository` (globalny stan sesji jako `StateFlow<Boolean>`), `LoginScreen` +
`LoginViewModel`.

Mechanizm: Laravel Sanctum cookie mode — `GET /sanctum/csrf-cookie` → `POST /auth/login` →
sesja w ciasteczku `crowncapsinfo-session`, CSRF token w `XSRF-TOKEN`. Zweryfikowano
2026-06-11 (patrz roadmap F-01).

## Current State Analysis

Istniejący punkt wejścia sieciowego: `di/NetworkModule.kt` — jeden `Retrofit.Builder` bez
`OkHttpClient`, bez `CookieJar`, bez interceptorów. Wszystkie wywołania anonimowe. Pole
`isInCollection: Boolean = false` w modelu `Cap` nigdy nie jest `true`.

Wzorzec DI: `@Singleton` serwisy Retrofit providerowane w `NetworkModule`. Repozytoria
`@Inject constructor`. ViewModele `@HiltViewModel @Inject constructor`.

Brak: OkHttp explicit dependency (Retrofit 3 przynosi go transitive), brak JitPack w repo,
brak jakiejkolwiek warstwy auth.

### Key Discoveries

- `di/NetworkModule.kt:44` — `Retrofit.Builder()` bez `.client(...)` — tu dodajemy OkHttp
- `model/UserPublic.kt:7` — model użytkownika już istnieje (`id`, `firstName`, `lastName`, `imageUrl`, `active`, `country`)
- `navigation/Screen.kt:5` — `sealed class Screen` — tu dodajemy `object Login`
- `MainActivity.kt:55` — `NavHost(startDestination = Screen.Home.route)` — tu wpinaamy Login composable
- Sanctum XSRF-TOKEN: wartość w ciasteczku jest URL-encoded — interceptor MUSI ją URL-decode przed ustawieniem jako header `X-XSRF-TOKEN`
- `Accept: application/json` wymagany na wszystkich requestach do backendu — bez niego Sanctum przy błędach zwraca HTML redirect zamiast JSON

## Desired End State

Po wdrożeniu wszystkich faz:

1. Użytkownik otwiera aplikację — stan sesji inicjalizowany przez sprawdzenie obecności ciasteczka
   `crowncapsinfo-session` w `PersistentCookieJar` (lazy, bez request sieciowego).
2. Użytkownik nawiguje do `Screen.Login` — widzi pola email + hasło + przycisk "Zaloguj się".
3. Po poprawnym logowaniu: `SessionRepository.isLoggedIn` = `true`, nawigacja powraca.
4. Istniejące endpointy `/api/v1/caps` zaczynają zwracać prawdziwy `isInCollection` (cookies wysyłane automatycznie przez CookieJar).
5. Po wylogowaniu lub 401 z dowolnego endpointu: `SessionRepository.isLoggedIn` = `false`, CookieJar wyczyszczony. Mechanizm: `SessionAuthenticator` (OkHttp Authenticator) + jawny `logout()` w `AuthRepository`.

Weryfikacja: zalogować się prawdziwymi danymi → sprawdzić w Logcat że `crowncapsinfo-session`
cookie istnieje → sprawdzić response `GET /api/v1/caps/latest` w Logcat — `isInCollection`
powinien być `true` dla posiadanych kapsli.

### Key Discoveries

- `settings.gradle` (project level) — wymaga dodania JitPack repository dla `PersistentCookieJar`
- `di/NetworkModule.kt` — wymaga `@ApplicationContext context: Context` w `provideRetrofitClient`
  lub w nowej metodzie `provideCookieJar`
- CSRF flow: `GET /sanctum/csrf-cookie` musi być wywołany PRZED `POST /auth/login`; jeśli
  XSRF-TOKEN nie istnieje w CookieJar, interceptor nic nie dodaje (serwer odrzuci request)

## What We're NOT Doing

- FR-003 (wyświetlanie statusu "posiadam"/"nie posiadam" w wynikach wyszukiwania) — to S-01
- FR-007 (akcja "kupuję" — `POST /data/catalog/caps/{id}/collection`) — to S-01
- FR-008 (zakładka "oczekuje na skatalogowanie") — to S-01
- Modyfikacja istniejących ViewModeli (Home, Countries, CapDetail, itp.) — to S-01
- Obsługa wygasłej sesji przez UI (tylko `AuthRepository` wylogowuje i ustawia stan false; S-01 dodaje redirect do Login)
- Ekran profilu / ustawień konta
- Zmiana hasła, rejestracja, reset hasła

## Implementation Approach

Trzy niezależne fazy budowane od dołu ku górze: sieć → domena → UI.

Faza 1 tworzy infrastrukturę HTTP (CookieJar, interceptory, `AuthApiService`).
Faza 2 buduje na niej logikę domenową (`SessionRepository`, `AuthRepository`).
Faza 3 tworzy UI konsumujące domenę.

Każda faza jest weryfikowalna samodzielnie przed przejściem do kolejnej.

## Critical Implementation Details

**URL-decoding XSRF-TOKEN**: wartość ciasteczka `XSRF-TOKEN` jest zakodowana URL-em przez
Laravel. `CsrfInterceptor` musi wywołać `URLDecoder.decode(token, "UTF-8")` zanim ustawi ją
jako nagłówek `X-XSRF-TOKEN`. Bez dekodowania serwer zwraca 419 (CSRF mismatch).

**Accept: application/json**: ten nagłówek musi być obecny na każdym requeście. Bez niego
Sanctum przy nieautoryzowanych requestach zwraca HTML redirect (302) zamiast JSON 401/422.
`AcceptJsonInterceptor` dodaje go globalnie.

**PersistentCookieJar potrzebuje kontekstu aplikacji**: konstruktor biblioteki przyjmuje
`CookieCache` i `PersistentCookieStore(context)`. `NetworkModule` musi przyjąć
`@ApplicationContext context: Context` w metodzie providerującej `PersistentCookieJar`.

---

## Phase 1: Networking Foundation

### Overview

Dodanie OkHttp client z `PersistentCookieJar`, `CsrfInterceptor`, `AcceptJsonInterceptor`
do `NetworkModule`. Stworzenie `AuthApiService`. Istniejące serwisy API (`CapApiService`,
`CountryApiService`, `CategoryApiService`) nie wymagają żadnych zmian.

### Changes Required

#### 1. JitPack repository

**File**: `settings.gradle` (projekt root, nie `app/settings.gradle`)

**Intent**: Dodać JitPack jako źródło zależności, żeby Gradle mógł pobrać `PersistentCookieJar`.

**Contract**: W bloku `dependencyResolutionManagement.repositories` dodaj:
`maven { url 'https://jitpack.io' }`

#### 2. Zależność PersistentCookieJar

**File**: `app/build.gradle`

**Intent**: Dodać bibliotekę `PersistentCookieJar` do zależności aplikacji.

**Contract**: W bloku `dependencies` dodaj:
`implementation 'com.github.franmontiel:PersistentCookieJar:v1.0.1'`

#### 3. CsrfInterceptor

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/auth/CsrfInterceptor.kt`

**Intent**: Interceptor OkHttp, który dla każdego POST/PUT/DELETE/PATCH odczytuje wartość
ciasteczka `XSRF-TOKEN` z CookieJar, dekoduje URL-encoding i ustawia ją jako nagłówek
`X-XSRF-TOKEN`. Dla GET/HEAD nic nie robi.

**Contract**: `class CsrfInterceptor(private val cookieJar: CookieJar) : Interceptor`.
Metoda `intercept` sprawdza `chain.request().method`, jeśli mutujący — ładuje cookies przez
`cookieJar.loadForRequest(request.url)`, szuka `XSRF-TOKEN`, URL-decode przez
`URLDecoder.decode(token, "UTF-8")`, buduje nowy request z `.header("X-XSRF-TOKEN", decoded)`.

#### 4. AcceptJsonInterceptor

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/auth/AcceptJsonInterceptor.kt`

**Intent**: Globalny interceptor dodający `Accept: application/json` do każdego requestu.
Zapobiega zwracaniu przez Sanctum HTML-owych redirectów zamiast JSON odpowiedzi na błędy.

**Contract**: `class AcceptJsonInterceptor : Interceptor`. W `intercept` buduje nowy request
z `.header("Accept", "application/json")` i przepuszcza dalej.

#### 5. NetworkModule — update

**File**: `app/src/main/java/pl/sroki/cci/android/di/NetworkModule.kt`

**Intent**: Dodać do modułu Hilt: provider `PersistentCookieJar` (singleton, potrzebuje
kontekstu), `OkHttpClient` (singleton, z CookieJar + obu interceptorami), a `Retrofit`
zaktualizować żeby używał tego klienta.

**Contract**:
- Nowa metoda `provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar`
  — tworzy `PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))`
- Nowa metoda `provideOkHttpClient(cookieJar: PersistentCookieJar): OkHttpClient`
  — buduje `OkHttpClient.Builder().cookieJar(cookieJar).addInterceptor(AcceptJsonInterceptor()).addInterceptor(CsrfInterceptor(cookieJar)).build()`
- Istniejąca metoda `provideRetrofitClient` — dodaj parametr `okHttpClient: OkHttpClient`
  i dodaj `.client(okHttpClient)` do buildera

#### 6. AuthApiService

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/auth/AuthApiService.kt`

**Intent**: Interfejs Retrofit dla endpointów autentykacji crowncaps.info (CSRF init, login,
logout, bieżący użytkownik).

**Contract**:
```
interface AuthApiService {
    @GET("sanctum/csrf-cookie")
    suspend fun initCsrf(): Response<Unit>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<Unit>

    @POST("logout")
    suspend fun logout(): Response<Unit>
}
```
`getCurrentUser()` celowo pominięty — F-01 go nie używa. Dodać w planie S-01.
Gdzie `LoginRequest` to `@Serializable data class LoginRequest(val email: String, val password: String)`.

#### 7. AuthApiService Hilt provider

**File**: `app/src/main/java/pl/sroki/cci/android/di/NetworkModule.kt`

**Intent**: Dodać provider `AuthApiService` do `NetworkModule` — analogicznie do istniejących
providerów `CapApiService`, `CountryApiService`.

**Contract**: Nowa metoda `provideAuthApiService(retrofit: Retrofit): AuthApiService`
z `@Singleton @Provides`.

#### 8. LoginRequest model

**File**: `app/src/main/java/pl/sroki/cci/android/model/LoginRequest.kt`

**Intent**: Data class dla ciała POST `/auth/login`.

**Contract**: `@Serializable data class LoginRequest(val email: String, val password: String)`

### Success Criteria

#### Automated Verification

- Projekt kompiluje się bez błędów: `./gradlew :app:compileDebugKotlin`
- Brak błędów Hilt przy generacji kodu: `./gradlew :app:kspDebugKotlin`
- Lint przechodzi: `./gradlew :app:ktlintCheck`

#### Manual Verification

- Aplikacja uruchamia się bez crash na zimnym starcie
- W Logcat widać że Retrofit używa OkHttp client (brak `IllegalStateException` o brakującym kliencie)
- Istniejące ekrany (Home, Countries, CapDetail) działają bez regresji — kapsle ładują się normalnie

**Implementation Note**: Po przejściu tej fazy potwierdź ręcznie, że istniejące funkcje aplikacji działają bez regresji, zanim przejdziesz do Phase 2.

---

## Phase 2: Auth Domain Layer

### Overview

Stworzenie `SessionRepository` (globalny stan sesji, `StateFlow<Boolean>`), `SessionAuthenticator`
(globalny interceptor 401 → logout), i `AuthRepository` (pełny flow login/logout, lazy init
ze sprawdzania ciasteczek, obsługa błędów API). `NetworkModule` aktualizowany drugi raz —
doklejenie authenticatora do OkHttpClient.

### Changes Required

#### 1. SessionRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/SessionRepository.kt`

**Intent**: Singleton trzymający binarny stan zalogowania jako `StateFlow<Boolean>`. Służy
jako single source of truth dla całej aplikacji. ViewModele wstrzykują go i reaktywnie
reagują na zmiany stanu.

**Contract**:
```
@Singleton
class SessionRepository @Inject constructor() {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    fun setLoggedIn(value: Boolean) { _isLoggedIn.value = value }
}
```

#### 2. SessionAuthenticator

**File**: `app/src/main/java/pl/sroki/cci/android/data/datasource/remote/auth/SessionAuthenticator.kt`

**Intent**: OkHttp `Authenticator` przechwytujący odpowiedzi 401 z dowolnego endpointu.
Na 401: czyści CookieJar lokalnie i ustawia `SessionRepository.isLoggedIn = false`. Zwraca
`null` — brak retry. Realizuje obietnicę End State #5 ("lub 401 → logout").

**Contract**: `class SessionAuthenticator(private val cookieJar: PersistentCookieJar,
private val sessionRepository: SessionRepository) : Authenticator`.
Metoda `authenticate`: `cookieJar.clear(); sessionRepository.setLoggedIn(false); return null`.

#### 3. NetworkModule — aktualizacja OkHttpClient (Phase 2 patch)

**File**: `app/src/main/java/pl/sroki/cci/android/di/NetworkModule.kt`

**Intent**: Dodać provider `SessionAuthenticator` i zaktualizować `provideOkHttpClient`
żeby wstrzykiwał authenticatora. Singleton OkHttpClient tworzony jeden raz — nowe parametry
Hilt wstrzykuje automatycznie.

**Contract**:
- Nowa metoda `provideSessionAuthenticator(cookieJar: PersistentCookieJar, sessionRepository: SessionRepository): Authenticator`
- Zaktualizuj `provideOkHttpClient` — dodaj parametr `authenticator: Authenticator`
  i dołącz `.authenticator(authenticator)` do OkHttpClient.Builder

#### 4. AuthRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt`

**Intent**: Singleton odpowiedzialny za: (a) lazy init — sprawdzenie ciasteczka przy
konstruowaniu; (b) flow logowania (getCsrfCookie → login → setLoggedIn(true)); (c) logout
(API call + wyczyszczenie CookieJar + setLoggedIn(false)); (d) obsługę błędów logowania
(422 → komunikat z pola `errors.email`, inne kody → generyczny błąd).

**Contract**:
```
@Singleton
class AuthRepository @Inject constructor(
    private val authApiService: AuthApiService,
    private val sessionRepository: SessionRepository,
    private val cookieJar: PersistentCookieJar
) {
    init {
        val hasSession = cookieJar
            .loadForRequest("https://crowncaps.info/".toHttpUrl())
            .any { it.name == "crowncapsinfo-session" }
        sessionRepository.setLoggedIn(hasSession)
    }

    suspend fun login(email: String, password: String): Result<Unit>
    suspend fun logout()
}
```

Metoda `login`:
- Cała metoda opakowana w `try { } catch (e: Exception) { return Result.failure(e) }` — sieć może nie być dostępna
- Wywołuje `authApiService.initCsrf()` (ustawia XSRF-TOKEN w CookieJar)
- Wywołuje `authApiService.login(LoginRequest(email, password))`
- `200` → `sessionRepository.setLoggedIn(true)` → `Result.success(Unit)`
- `422` → parsuje body JSON (patrz niżej), zwraca `Result.failure(Exception(errors.email[0]))`
- inne → `Result.failure(Exception("Błąd logowania: $code"))`

Metoda `logout`:
- Wywołuje `authApiService.logout()` (best-effort, ignoruje błędy sieciowe)
- Wywołuje `cookieJar.clear()` — usuwa wszystkie ciasteczka lokalnie
- Wywołuje `sessionRepository.setLoggedIn(false)`

#### 5. LoginErrorResponse model

**File**: `app/src/main/java/pl/sroki/cci/android/model/LoginErrorResponse.kt`

**Intent**: Model odpowiedzi błędu z `/auth/login` (HTTP 422). Używany przez `AuthRepository`
do wyciągnięcia czytelnego komunikatu z pola `errors.email`.

**Contract**: `@Serializable data class LoginErrorResponse(val errors: Map<String, List<String>>)`

Użycie w `login()` przy 422:
```kotlin
val raw = response.errorBody()?.string() ?: ""
val err = Json { ignoreUnknownKeys = true }.decodeFromString<LoginErrorResponse>(raw)
Result.failure(Exception(err.errors["email"]?.firstOrNull() ?: "Błąd logowania"))
```

#### 6. Testy jednostkowe AuthRepository

**File**: `app/src/test/java/pl/sroki/cci/android/data/AuthRepositoryTest.kt`

**Intent**: Przetestować: (a) lazy init — jeśli cookie istnieje → `isLoggedIn = true`;
(b) `login()` sukces → `isLoggedIn = true`, `Result.success`; (c) `login()` błąd 422 →
`isLoggedIn = false`, `Result.failure` z komunikatem z API; (d) `logout()` → `isLoggedIn = false`,
cookies wyczyszczone.

**Contract**: Użyj `mockk` + `runTest` (coroutines-test). Mock `AuthApiService` i
`PersistentCookieJar`. `SessionRepository` użyj jako real instance (to prosty holder StateFlow).

### Success Criteria

#### Automated Verification

- Testy jednostkowe przechodzą: `./gradlew :app:test`
- Projekt kompiluje się: `./gradlew :app:compileDebugKotlin`

#### Manual Verification

- Debugowy log w `AuthRepository.init()` (można dodać tymczasowo): "Session cookie found: true/false"
- `SessionRepository.isLoggedIn` w runtime zwraca `false` przy pierwszym uruchomieniu (brak ciasteczek)

**Implementation Note**: Potwierdź że testy przechodzą i że `AuthRepository` inicjalizuje się
bez błędów Hilt (sprawdź Logcat przy starcie aplikacji), zanim przejdziesz do Phase 3.

---

## Phase 3: Login UI

### Overview

Ekran logowania (`LoginScreen` + `LoginViewModel`) wpięty jako dedykowana trasa w NavGraph.
Dostępny przez `navController.navigate(Screen.Login.route)`. Po udanym logowaniu powrót
przez `navController.popBackStack()`.

### Changes Required

#### 1. Screen.Login trasa

**File**: `app/src/main/java/pl/sroki/cci/android/navigation/Screen.kt`

**Intent**: Dodać `Login` jako nowy obiekt trasy do sealed class.

**Contract**: `object Login : Screen("login")` — dodaj analogicznie do istniejących obiektów.

#### 2. LoginViewModel

**File**: `app/src/main/java/pl/sroki/cci/android/ui/auth/LoginViewModel.kt`

**Intent**: ViewModel dla ekranu logowania. Zarządza stanem UI (Idle/Loading/Success/Error),
wywołuje `AuthRepository.login()`, eksponuje wynik przez `StateFlow<LoginUiState>`.

**Contract**:
```
sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    val uiState: StateFlow<LoginUiState>
    fun login(email: String, password: String)
}
```

`login()` ustawia `Loading`, wywołuje `authRepository.login()` w `viewModelScope`,
ustawia `Success` lub `Error(message)`.

#### 3. LoginScreen

**File**: `app/src/main/java/pl/sroki/cci/android/ui/auth/LoginScreen.kt`

**Intent**: Composable ekranu logowania: pole email, pole hasła (z obscuredText), przycisk
"Zaloguj się" (disabled gdy Loading), komunikat błędu gdy Error, wywołuje `onLoginSuccess`
callback gdy Success.

**Contract**: `@Composable fun LoginScreen(onLoginSuccess: () -> Unit, viewModel: LoginViewModel = hiltViewModel())`.
Collectuje `uiState` przez `collectAsState()`. Używa `Material3 OutlinedTextField`, `Button`,
`CircularProgressIndicator` — zgodnie ze wzorcem z istniejących ekranów. Po `Success` — wywołuje
`onLoginSuccess`.

#### 4. NavHost — wpięcie LoginScreen

**File**: `app/src/main/java/pl/sroki/cci/android/MainActivity.kt`

**Intent**: Dodać `composable(Screen.Login.route) { LoginScreen(...) }` do istniejącego
`NavHost`, żeby trasa była dostępna. Przekazać `onLoginSuccess = { navController.popBackStack() }`.

**Contract**: Nowy blok `composable(route = Screen.Login.route) { LoginScreen(onLoginSuccess = { navController.popBackStack() }) }` — dodaj w `NavHost` analogicznie do istniejących tras.

### Success Criteria

#### Automated Verification

- Projekt kompiluje się bez błędów: `./gradlew :app:compileDebugKotlin`
- Brak błędów Hilt: `./gradlew :app:kspDebugKotlin`
- Lint: `./gradlew :app:ktlintCheck`

#### Manual Verification

- Nawigacja do `Screen.Login.route` wyświetla ekran logowania (można wywołać z dowolnego miejsca tymczasowo)
- Wpisanie błędnych danych → przycisk → pojawia się komunikat błędu z API ("These credentials do not match...")
- Wpisanie poprawnych danych → przycisk → krótki stan Loading → sukces → nawigacja powraca
- Po zalogowaniu: `SessionRepository.isLoggedIn` = `true` (sprawdź przez tymczasowy log lub debugger)
- Po zalogowaniu: kolejne wywołanie `GET /api/v1/caps/latest` (np. otwarcie LatestCaps) zwraca `isInCollection = true` dla posiadanych kapsli (weryfikacja w Logcat przez `OkHttp` logging interceptor)

**Implementation Note**: Dodaj tymczasowo `HttpLoggingInterceptor` (OkHttp) na czas weryfikacji żeby zobaczyć request/response. Usuń przed commitem.

---

## Testing Strategy

### Unit Tests

- `AuthRepositoryTest.kt` — 4 scenariusze: lazy init (cookie present/absent), login success, login 422, logout

### Manual Testing Steps

1. Zimny start aplikacji — sprawdź Logcat: brak błędów, `SessionRepository.isLoggedIn = false`
2. Otwórz LatestCaps — kapsle ładują się normalnie (regresja check)
3. Nawiguj do Login przez tymczasowy przycisk w Home (lub modyfikuj `startDestination` tymczasowo)
4. Zaloguj się błędnymi danymi — widoczny komunikat błędu
5. Zaloguj się poprawnymi danymi — sukces, powrót do poprzedniego ekranu
6. Otwórz LatestCaps — w Logcat (OkHttp logging) `isInCollection` = `true` dla posiadanych kapsli
7. Zrestartuj aplikację — `SessionRepository.isLoggedIn = true` (ciasteczko persystowane)
8. Wyloguj się (API call) — `SessionRepository.isLoggedIn = false`

## Migration Notes

Brak danych do migracji. Istniejące wywołania API nie wymagają zmian — CookieJar jest
transparentny dla anonimowych wywołań.

## References

- Roadmap F-01: `context/foundation/roadmap.md` (sekcja "Auth API contract")
- API verification: przeprowadzona 2026-06-11, wyniki w roadmap F-01
- PersistentCookieJar: https://github.com/franmontiel/PersistentCookieJar
- Istniejący NetworkModule: `app/src/main/java/pl/sroki/cci/android/di/NetworkModule.kt:19`
- Model UserPublic: `app/src/main/java/pl/sroki/cci/android/model/UserPublic.kt:7`
- Istniejące trasy: `app/src/main/java/pl/sroki/cci/android/navigation/Screen.kt:5`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Networking Foundation

#### Automated

- [x] 1.1 Projekt kompiluje się bez błędów: `./gradlew :app:compileDebugKotlin`
- [x] 1.2 Brak błędów Hilt: `./gradlew :app:kspDebugKotlin`
- [x] 1.3 Lint przechodzi: `./gradlew :app:ktlintCheck`

#### Manual

- [x] 1.4 Aplikacja uruchamia się bez crash na zimnym starcie
- [x] 1.5 Istniejące ekrany działają bez regresji (Home, Countries, CapDetail ładują kapsle normalnie)

### Phase 2: Auth Domain Layer

#### Automated

- [ ] 2.1 Testy jednostkowe przechodzą: `./gradlew :app:test`
- [ ] 2.2 Projekt kompiluje się: `./gradlew :app:compileDebugKotlin`

#### Manual

- [ ] 2.3 Logcat przy starcie: brak błędów Hilt, `AuthRepository` inicjalizuje się poprawnie
- [ ] 2.4 `SessionRepository.isLoggedIn = false` przy pierwszym uruchomieniu (brak ciasteczek)

### Phase 3: Login UI

#### Automated

- [ ] 3.1 Projekt kompiluje się bez błędów: `./gradlew :app:compileDebugKotlin`
- [ ] 3.2 Brak błędów Hilt: `./gradlew :app:kspDebugKotlin`
- [ ] 3.3 Lint: `./gradlew :app:ktlintCheck`

#### Manual

- [ ] 3.4 Nawigacja do `Screen.Login` wyświetla ekran logowania
- [ ] 3.5 Błędne dane → komunikat błędu z API
- [ ] 3.6 Poprawne dane → Loading → sukces → powrót do poprzedniego ekranu
- [ ] 3.7 Po zalogowaniu i restarcie aplikacji `SessionRepository.isLoggedIn = true`
- [ ] 3.8 Kapsle w LatestCaps po zalogowaniu mają `isInCollection = true` dla posiadanych (Logcat)

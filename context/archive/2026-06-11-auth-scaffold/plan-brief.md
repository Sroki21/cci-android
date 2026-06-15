# Auth Scaffold — Plan Brief

**Change:** `auth-scaffold` | **Roadmap:** F-01 | **Status:** planning

## What changes and why

Dodanie fundamentu autentykacji do CCI Android. Bez tej zmiany wszystkie wywołania API są
anonimowe — pole `isInCollection` zawsze `false`. Cel: użytkownik loguje się kontem
crowncaps.info, sesja persystuje między uruchomieniami, zalogowane requesty zwracają prawdziwy
status kolekcji.

Mechanizm: Laravel Sanctum cookie mode. Zweryfikowany 2026-06-11 przez lokalne curl-testy.

## Three phases

| Phase | Scope | Key deliverables |
|-------|-------|-----------------|
| 1 | Networking | `PersistentCookieJar` + `CsrfInterceptor` + `AcceptJsonInterceptor` + `AuthApiService`; update `NetworkModule` |
| 2 | Auth domain | `SessionRepository` (StateFlow<Boolean>) + `AuthRepository` (login/logout/lazy init) + unit tests |
| 3 | Login UI | `Screen.Login` + `LoginViewModel` + `LoginScreen` + wpięcie do NavHost |

## Files touched

**Phase 1** (6 plików):

| File | Action |
|------|--------|
| `settings.gradle` | add JitPack repository |
| `app/build.gradle` | add `PersistentCookieJar:v1.0.1` |
| `di/NetworkModule.kt` | add CookieJar + OkHttpClient providers; wire into Retrofit |
| `data/datasource/remote/auth/CsrfInterceptor.kt` | create |
| `data/datasource/remote/auth/AcceptJsonInterceptor.kt` | create |
| `data/datasource/remote/auth/AuthApiService.kt` | create |
| `model/LoginRequest.kt` | create |

**Phase 2** (6 plików):

| File | Action |
|------|--------|
| `data/SessionRepository.kt` | create |
| `data/datasource/remote/auth/SessionAuthenticator.kt` | create (401 → logout) |
| `di/NetworkModule.kt` | update — add SessionAuthenticator + wire into OkHttpClient |
| `model/LoginErrorResponse.kt` | create |
| `data/AuthRepository.kt` | create |
| `test/data/AuthRepositoryTest.kt` | create (4 test cases) |

**Phase 3** (4 pliki):

| File | Action |
|------|--------|
| `navigation/Screen.kt` | add `Login` object |
| `ui/auth/LoginViewModel.kt` | create |
| `ui/auth/LoginScreen.kt` | create |
| `MainActivity.kt` | add Login composable to NavHost |

## Critical implementation details

1. **XSRF-TOKEN URL-decode**: `CsrfInterceptor` musi wywołać `URLDecoder.decode(token, "UTF-8")` przed ustawieniem `X-XSRF-TOKEN`. Bez tego serwer zwraca 419.
2. **Accept: application/json**: `AcceptJsonInterceptor` wymagany globalnie — bez niego Sanctum zwraca HTML redirect przy błędach zamiast JSON.
3. **PersistentCookieJar** potrzebuje kontekstu aplikacji — `NetworkModule` przyjmuje `@ApplicationContext context: Context`.
4. **Lazy session init** — `AuthRepository.init {}` sprawdza `crowncapsinfo-session` w CookieJar lokalnie (zero requestów sieciowych przy zimnym starcie).
5. **Logout = API call + cookieJar.clear()** — lokalne wyczyszczenie ciasteczek obowiązkowe.
6. **401 globalny** — `SessionAuthenticator` (Phase 2): OkHttp Authenticator, na 401: `cookieJar.clear()` + `setLoggedIn(false)` + `return null`. Brak circular dep.
7. **login() try-catch** — `IOException` z sieci musi być przechwycony; cała metoda `login()` owrapowana w `try-catch(Exception)`.
8. **422 parsing** — `LoginErrorResponse(@Serializable)` + `response.errorBody()?.string()` + `Json.decodeFromString`.

## What we're not doing

S-01 (wyświetlanie `isInCollection` w UI, akcja "kupuję", zakładka oczekujących) — to oddzielna
zmiana, odblokowana przez F-01.

## Success gate (przed commitem)

- `./gradlew :app:test` — zielone
- `./gradlew :app:compileDebugKotlin` + `kspDebugKotlin` — czyste
- Ręczna weryfikacja: poprawne dane → Loading → sukces → powrót; restart → sesja utrzymana

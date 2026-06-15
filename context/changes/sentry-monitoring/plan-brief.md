# Sentry Monitoring — Plan Brief

> Full plan: `context/changes/sentry-monitoring/plan.md`

## What & Why

Integracja Sentry Android SDK z CCI Android — automatyczne raportowanie crashy/ANR oraz manualne `captureException()` w dwóch kluczowych miejscach (Firestore restore, `fetchApiToken`). Projekt nie ma żadnego crash reportingu; błędy produkcyjne są niewidoczne poza `adb logcat`.

## Starting Point

Brak crash reporting. `CCIApplication.kt` jest czystą klasą Application z Hilt. `app/gradle.lockfile` jest aktywny — wymaga aktualizacji po dodaniu zależności. `AuthRepository.kt` loguje błędy auth przez `Log.w` bez raportowania.

## Desired End State

Każdy crash/ANR oraz błędy `fetchApiToken` i Firestore restore są widoczne w dashboardzie Sentry z Firebase UID powiązanym z eventem. Pierwsze eventy pojawiają się po pierwszym uruchomieniu po deploymencie.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Zakres | Crashe + 2 caught exceptions | Minimalne, ale pokrywa miejsca gdzie błąd jest najtrudniejszy do odtworzenia | Plan |
| DSN storage | AndroidManifest meta-data | Najprostsze; dla prywatnej aplikacji widoczność DSN w APK jest akceptowalna | Plan |
| SDK version | `7.+` (stable 7.x) | Dojrzała kompatybilność z minSdk 24, Kotlin, Hilt | Plan |
| User context | Firebase UID | Prywatna 1-osobowa app — UID wystarczy do identyfikacji | Plan |
| OkHttp / breadcrumby | NIE | Nie zażądane, można dodać osobną zmianą w przyszłości | Plan |

## Scope

**In scope:**
- `io.sentry:sentry-android:7.+` w build.gradle + lockfile
- DSN jako `<meta-data>` w AndroidManifest
- `SentryAndroid.init()` w `CCIApplication.onCreate()` (przed Hilt)
- `Sentry.setUser(Firebase UID)` po `ensureSignedIn()`
- `Sentry.captureException(e)` w catch bloku Firestore restore
- `Sentry.captureException(it)` w `fetchApiToken.onFailure`

**Out of scope:**
- OkHttp interceptor / HTTP tracking
- Breadcrumby użytkownika
- Performance monitoring
- `captureException` w ViewModelach
- `captureException` przy `signInWithEmail.onFailure` (oczekiwana ścieżka)

## Architecture / Approach

Sentry init jako pierwsze w `Application.onCreate()`, przed `super.onCreate()` (Hilt) i coroutine launch — żeby SDK złapało błędy jak najwcześniej w cyklu życia aplikacji. DSN czytany automatycznie z manifestu. User context ustawiany asynchronicznie po Firebase auth.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. SDK + init + exceptions | Sentry działa z auto crash reporting + 2 caught exceptions + user context | DSN w manifeście — trzeba zastąpić placeholder prawdziwym DSN przed testem |

**Prerequisites:** Aktywne konto Sentry + projekt Android z DSN  
**Estimated effort:** ~1 sesja, 5 plików

## Open Risks & Assumptions

- DSN jest placeholderem w planie — użytkownik musi wpisać własny DSN z sentry.io przed uruchomieniem
- `7.+` rozwiązuje się do konkretnej wersji podczas `--write-locks` — jeśli API 7.x zmieniło się po cutoff wiedzy modelu, może być potrzebna korekta importów
- Firebase UID może być `null` przy pierwszym uruchomieniu przed zalogowaniem — `let` guard obsługuje ten przypadek

## Success Criteria (Summary)

- `./gradlew compileDebugKotlin` przechodzi bez błędów
- Po uruchomieniu aplikacji na urządzeniu event testowy pojawia się w dashboardzie Sentry
- Event zawiera Firebase UID w polu User

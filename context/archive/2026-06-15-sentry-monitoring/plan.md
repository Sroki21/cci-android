---
change_id: sentry-monitoring
title: Sentry monitoring — crash reporting i schwytane błędy
status: planned
created: 2026-06-15
updated: 2026-06-15
---

# Sentry Monitoring — Implementation Plan

## Overview

Integracja Sentry Android SDK z aplikacją CCI Android. Zakres: automatyczne raportowanie crashy i ANR (zero kodu poza init), manualne `captureException()` w dwóch kluczowych miejscach (Firestore restore, `fetchApiToken`), oraz kontekst użytkownika (Firebase UID) dołączony do każdego eventu.

## Current State Analysis

Projekt nie ma żadnego crash reporting. Istniejące logi:
- `Log.w` w `AuthRepository` przy błędach auth i tokenu
- `Log.d` (diagnostyczne) w kilku miejscach
- Catch block w `CCIApplication.onCreate` połyka błędy Firestore restore (non-fatal, intentional)

Infrastruktura gotowa do integracji:
- `CCIApplication.kt` — czysta klasa Application z Hilt, miejsce na `SentryAndroid.init()`
- `AndroidManifest.xml` — brak istniejących SDK meta-data
- `app/gradle.lockfile` — aktywny dependency locking, wymaga aktualizacji po dodaniu SDK

## Desired End State

Po implementacji:
- Każdy crash/ANR jest automatycznie raportowany do Sentry z pełnym stack trace i Firebase UID
- Błędy `fetchApiToken` i Firestore restore są raportowane do Sentry niezależnie od tego czy powodują crash
- Dashboard Sentry pokazuje eventy z identyfikatorem użytkownika

Weryfikacja: uruchom `./gradlew compileDebugKotlin` bez błędów; uruchom aplikację na urządzeniu i sprawdź w dashboardzie Sentry czy event testowy dotarł.

### Key Discoveries

- `CCIApplication.kt:21` — `onCreate()` wywołuje `super.onCreate()` po czym startuje coroutine. Sentry init musi być PRZED `super.onCreate()` (Hilt inject) i PRZED async launch.
- `app/gradle.lockfile` — dependency locking aktywny; po dodaniu zależności wymagane `./gradlew :app:dependencies --write-locks`.
- DSN w `AndroidManifest.xml` jako `<meta-data android:name="io.sentry.dsn">` — Sentry SDK czyta go automatycznie, nie trzeba przekazywać DSN do `SentryAndroid.init()`.
- `FirebaseAuthManager.uid` to `StateFlow<String?>` — wartość jest dostępna synchronicznie po `ensureSignedIn()`.
- `firebaseAuthManager.signInWithEmail.onFailure` — celowo NIE dostaje `captureException`; Firebase sign-in failure jest normalną ścieżką przy pierwszym logowaniu (brak konta → create).

## What We're NOT Doing

- OkHttp/Retrofit interceptor — nie zażądany
- Breadcrumby w akcjach użytkownika — nie zażądane
- Performance monitoring (transactions) — nie zażądany
- `captureException` w ViewModelach — zakres: tylko auth i Firestore
- `captureException` przy `signInWithEmail.onFailure` — oczekiwana ścieżka przy nowym koncie

## Implementation Approach

Pojedyncza faza: SDK → manifest DSN → init w Application → caught exceptions → user context.
Sentry jest zainicjalizowany jako pierwsze w `Application.onCreate()` żeby złapać błędy Hilt inject i coroutine launch.

---

## Phase 1: Sentry SDK, init, user context i caught exceptions

### Overview

Dodanie SDK, konfiguracja DSN w manifeście, inicjalizacja w `CCIApplication`, ustawienie Firebase UID jako Sentry user, oraz `captureException()` w dwóch catch blokach.

### Changes Required

#### 1. Zależność SDK

**File**: `app/build.gradle`

**Intent**: Dodaj Sentry Android SDK do zależności aplikacji.

**Contract**: W bloku `dependencies {}` dodaj linię po `androidTestImplementation "io.mockk:mockk-android:1.14.11"`:
```groovy
implementation "io.sentry:sentry-android:7.+"
```

#### 2. Aktualizacja lockfile

**File**: `app/gradle.lockfile`

**Intent**: Zaktualizuj plik lockfile po dodaniu nowej zależności.

**Contract**: Uruchom `./gradlew :app:dependencies --write-locks`. Gradle rozwiąże `7.+` do konkretnej wersji 7.x i wpisze ją do lockfile. Nie edytuj lockfile ręcznie.

#### 3. DSN w AndroidManifest

**File**: `app/src/main/AndroidManifest.xml`

**Intent**: Dostarcz DSN do Sentry SDK przez manifest — SDK czyta go automatycznie przy inicjalizacji.

**Contract**: Wewnątrz tagu `<application>`, przed `<activity>`, dodaj:
```xml
<meta-data
    android:name="io.sentry.dsn"
    android:value="REPLACE_WITH_YOUR_SENTRY_DSN" />
```
Użytkownik musi zastąpić `REPLACE_WITH_YOUR_SENTRY_DSN` prawdziwym DSN ze swojego projektu Sentry (Settings → Projects → Client Keys).

#### 4. Inicjalizacja Sentry w CCIApplication

**File**: `app/src/main/java/pl/sroki/cci/android/CCIApplication.kt`

**Intent**: Zainicjalizuj Sentry jako pierwsze w `onCreate()`, przed Hilt i coroutine launch, żeby SDK złapało błędy jak najwcześniej. Po udanym Firebase sign-in ustaw UID jako Sentry user. W catch bloku Firestore restore dodaj `captureException`.

**Contract**:
- Dodaj importy: `io.sentry.Sentry`, `io.sentry.android.core.SentryAndroid`, `io.sentry.protocol.User`
- `SentryAndroid.init(this) { options -> options.isDebug = BuildConfig.DEBUG }` jako pierwsza linia `onCreate()`, PRZED `super.onCreate()`
- Po `firebaseAuthManager.ensureSignedIn()` ustaw user:
  ```kotlin
  firebaseAuthManager.uid.value?.let { uid ->
      Sentry.setUser(User().apply { id = uid })
  }
  ```
- W catch bloku dodaj `Sentry.captureException(e)` przed istniejącym komentarzem

#### 5. captureException w AuthRepository

**File**: `app/src/main/java/pl/sroki/cci/android/data/AuthRepository.kt`

**Intent**: Raportuj do Sentry błąd pobierania tokenu API — jest to nieoczekiwany błąd sieci/auth który już logujemy przez `Log.w`.

**Contract**: Dodaj import `io.sentry.Sentry`. W `runCatching { fetchApiToken(email, password) }.onFailure { }` dodaj `Sentry.captureException(it)` obok istniejącego `Log.w`:
```kotlin
runCatching { fetchApiToken(email, password) }
    .onFailure {
        Sentry.captureException(it)
        Log.w("CCI_AUTH", "api token fetch failed: ${it.message}")
    }
```

### Success Criteria

#### Automated Verification

- `./gradlew compileDebugKotlin` — kompilacja bez błędów po dodaniu SDK i zmianach w kodzie
- `./gradlew :app:dependencies --write-locks` — lockfile zaktualizowany bez konfliktów

#### Manual Verification

- Uruchom aplikację na urządzeniu/emulatorze i sprawdź w dashboardzie Sentry (sekcja Issues / Events) czy pojawiło się zdarzenie testowe lub session
- Wymuś test crash (np. tymczasowo rzuć wyjątek w `CCIApplication.onCreate()`) i zweryfikuj że event dotarł do Sentry z Firebase UID w polu User
- Przywróć kod po teście

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands.

### Phase 1: Sentry SDK, init, user context i caught exceptions

#### Automated

- [x] 1.1 Kompilacja — `./gradlew compileDebugKotlin` — brak błędów po dodaniu SDK — e06ad38
- [x] 1.2 Lockfile — `./gradlew :app:dependencies --write-locks` — bez konfliktów — e06ad38

#### Manual

- [x] 1.3 Event testowy dotarł do Sentry dashboard (session lub wymuszony crash)
- [x] 1.4 Firebase UID widoczny w polu User na evencie Sentry

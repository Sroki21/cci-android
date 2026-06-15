# CI/CD Pipeline — Implementation Plan

## Overview

Dodanie GitHub Actions workflow uruchamiającego testy jednostkowe i ktlint na każdym
push do `main`. Projekt ma już działający test runner (JUnit 4 + MockK) i linter (ktlint),
a `google-services.json` jest w repozytorium — workflow nie wymaga żadnego zarządzania
sekretami.

## Current State Analysis

- `.github/dependabot.yml` — aktywny (weekly Gradle updates), ale brak `workflows/`.
- `./gradlew testDebugUnitTest` — 23 testy jednostkowe, wszystkie zielone.
- `./gradlew ktlintCheck` — przechodzi czysto.
- `google-services.json` — obecny w repo (`app/google-services.json`), nie wyklucza `.gitignore`.
- `keystore.properties` — wykluczone z VCS, ale signing jest tylko dla `release`; debug
  build i unit testy nie wymagają pliku.
- `jvmToolchain(21)` w `app/build.gradle` — Gradle oczekuje JDK 21; `setup-java@v4`
  z `distribution: temurin` i `java-version: '21'` ustawia `JAVA_HOME`, które toolchain
  auto-detect podnosi.
- Gradle 9.5.1 wrapper, `lockAllConfigurations()` aktywne — lockfile jest w repo, CI
  dostaje go automatycznie.

## Desired End State

Po każdym push do `main` GitHub uruchamia job, który checkout-uje kod, ustawia JDK 21,
restoruje cache Gradle, uruchamia unit testy i ktlint. Zielona odznaka = `main` jest zdrowy.
Czerwona = natychmiastowa informacja o regresji lub naruszeniu stylu.

### Key Discoveries

- `setup-java@v4` z `cache: 'gradle'` obsługuje caching `~/.gradle/caches` i
  `~/.gradle/wrapper` bez osobnego kroku `actions/cache` — klucz cache oparty o
  `**.gradle*`, `gradle-wrapper.properties` i `gradle.lockfile`.
- `chmod +x gradlew` wymagane na `ubuntu-latest` — `gradlew` nie ma bitu execute
  po checkout na Linuxie.
- Gradle toolchain auto-detection działa gdy `JAVA_HOME` wskazuje na właściwą wersję
  — `setup-java` to zapewnia.

## What We're NOT Doing

- Testy instrumentowane (wymagają emulatora — inna historia).
- `assembleRelease` / podpisywanie APK — `keystore.properties` nie jest w VCS.
- Wdrożenie / upload APK na Firebase App Distribution lub Play — poza zakresem.
- Triggery na PR lub inne gałęzie — tylko `push: main`.

## Implementation Approach

Jeden plik YAML. Krok `setup-java@v4` z `cache: 'gradle'` zastępuje osobny
`actions/cache` — mniej konfiguracji, ten sam efekt. Fail-fast (domyślne zachowanie)
zatrzymuje job przy pierwszym błędzie.

---

## Phase 1: Workflow GitHub Actions

### Overview

Stworzyć `.github/workflows/android.yml` implementujący pipeline CI.

### Changes Required

#### 1. Plik workflow

**File**: `.github/workflows/android.yml`

**Intent**: Zdefiniować job `ci` uruchamiany na każdy push do `main`, który weryfikuje
testy jednostkowe i styl kodu.

**Contract**: Pełna treść pliku — tu snippet jest konieczny, bo kontrakt jest plikiem YAML:

```yaml
name: CI

on:
  push:
    branches: [ main ]

jobs:
  ci:
    name: Test & Lint
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Run unit tests
        run: ./gradlew testDebugUnitTest

      - name: Run ktlint
        run: ./gradlew ktlintCheck
```

### Success Criteria

#### Automated Verification

- Plik istnieje: `ls .github/workflows/android.yml`
- YAML poprawny składniowo (brak błędów parse w GitHub UI po push)
- Job zielony: wszystkie 5 kroków przechodzi (`✓` w GitHub Actions tab)
- Logi: `23 tests, 0 failures` w kroku testów
- Logi: `BUILD SUCCESSFUL` w kroku ktlint

#### Manual Verification

- W zakładce Actions na GitHub widoczny run dla ostatniego push do `main`
- Cache hit przy drugim uruchomieniu (logi kroku `Set up JDK 21` zawierają
  `Cache hit for key: Linux-gradle-...`)
- Czas buildu: pierwsze uruchomienie ~5-8 min, kolejne ~2-3 min
- Zielona odznaka statusu na stronie repo (po dodaniu badge — opcjonalne)

**Implementation Note**: Jeden commit — jeden plik. Po push weryfikuj w zakładce
Actions na GitHub (≈ 5 min na pierwsze uruchomienie).

---

## Testing Strategy

### Automated

CI jest sam w sobie testem infrastruktury — weryfikacja polega na obserwacji zielonego
joba na GitHub Actions.

### Manual Testing Steps

1. Utwórz plik, commit, push do `main`.
2. Wejdź w `github.com/<repo>/actions` — powinien pojawić się run dla commitu.
3. Sprawdź logi każdego kroku: checkout → setup-java → chmod → tests → ktlint.
4. Upewnij się, że `23 tests` przechodzi bez `FAILED`.
5. (Opcjonalnie) Wprowadź celowy błąd ktlint, push, sprawdź że job jest czerwony.

## References

- Change identity: `context/changes/ci-cd/change.md`
- Health check (wskazał CI jako Category B): `context/foundation/health-check.md`
- Stack assessment: `context/foundation/stack-assessment.md`
- Dependabot config (aktywny): `.github/dependabot.yml`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Workflow GitHub Actions

#### Automated

- [x] 1.1 Plik `.github/workflows/android.yml` istnieje — 129b551
- [ ] 1.2 Job `ci` zielony na GitHub Actions (wszystkie kroki ✓)
- [ ] 1.3 Logi: `23 tests, 0 failures` w kroku testów
- [ ] 1.4 Logi: `BUILD SUCCESSFUL` w kroku ktlint

#### Manual

- [ ] 1.5 Run widoczny w zakładce Actions na GitHub po push do main
- [ ] 1.6 Cache hit przy drugim uruchomieniu (czas buildu ~2-3 min)

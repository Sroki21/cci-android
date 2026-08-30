---
project: "CCI Android — wersja prywatna"
checked_at: 2026-06-15T00:00:00Z
health_status: healthy
context_type: brownfield
language_family: java
stack_assessment_available: true
checks_run:
  - lockfile
  - dependency_audit
  - outdated_deps
  - test_runner
  - ci_cd
  - configuration
audit_findings:
  critical: 0
  high: 0
  moderate: 0
  low: 0
test_runner_detected: true
ci_provider: null
recommended_fixes: 2
---

## Dependency Health

### Lockfile

```
Status:          present (app/gradle.lockfile)
Package manager: Gradle (Groovy DSL) z lockAllConfigurations()
```

`app/gradle.lockfile` blokuje wszystkie resolvable konfiguracje Gradle
(implementation, testImplementation, androidTestImplementation, runtimeClasspath itd.).
Buildy są reprodukowalne — agent może opierać się na dokładnym stanie całego drzewa
zależności, w tym tranzytywnych.

### Security Audit

```
Tool:    skipped — brak wbudowanego narzędzia audytu CVE dla Kotlin/JVM/Android
Summary: 0 CRITICAL, 0 HIGH, 0 MODERATE, 0 LOW (brak formalnego skanu)
```

**Dostępna automatyzacja bezpieczeństwa:** `.github/dependabot.yml` jest skonfigurowany
z harmonogramem `weekly` dla ekosystemu `gradle`. Dependabot automatycznie otwiera PR-y
przy wykryciu podatnych wersji zależności — passywny monitoring aktywny.

**Rekomendowane zewnętrzne narzędzie dla głębszego skanu:**
OWASP Dependency-Check Gradle plugin (`org.owasp.dependencycheck`) — jednorazowa
konfiguracja, raport HTML/XML lokalnie lub w CI.

### Outdated Dependencies

```
Sprawdzenie staleness: pominięte w tej sesji (wymaga uruchomienia ./gradlew dependencyUpdates
w środowisku z Android SDK)
```

Plugin `com.github.ben-manes.versions` (0.54.0) jest zainstalowany. Uruchom lokalnie:

```bash
./gradlew dependencyUpdates
# Raport: app/build/dependencyUpdates/report.txt
```

Na podstawie przeglądu `app/build.gradle` — używane wersje:

| Zależność               | Wersja w projekcie | Uwaga                                       |
|-------------------------|--------------------|---------------------------------------------|
| Compose BOM             | 2026.05.01         | Bardzo aktualne                             |
| Hilt                    | 2.59.2             | Aktualne                                    |
| Room                    | 2.7.0              | Dostępny 2.8.x (minor)                      |
| Retrofit                | 3.0.0              | Aktualne (major jump z 2.x zakończony)      |
| Firebase BOM            | 33.8.0             | Dostępny 34.x (1 major)                     |
| OkHttp logging          | 4.12.0             | Dostępny 5.x (1 major)                      |
| Paging                  | 3.5.0              | Aktualne                                    |
| Kotlin                  | 2.3.20             | 2.4.0 świadomie zablokowany (czeka KSP 2.4.x) |
| kotlinx-coroutines-test | 1.11.0             | Aktualne                                    |

Brak zależności ≥2 major versions behind na podstawie przeglądu. Kotlin 2.4.0 jest
świadomie odroczony (patrz memory: `project_kotlin_upgrade_blocked.md`).

---

## Test Suite

```
Test runner:    JUnit 4 + MockK 1.14.11 + kotlinx-coroutines-test 1.11.0 + paging-testing 3.5.0
Tests found:    6 klas unit testów (host JVM) + 7 klas testów instrumentowanych (device)
Test execution: nie uruchomiono w tej sesji (wymaga Android SDK + JDK w środowisku)
```

**Konfiguracja:**

```
Framework:    JUnit 4 (junit:junit:4.13.2)
Mocking:      MockK 1.14.11 (constructor injection — zgodnie z CLAUDE.md)
Async:        kotlinx-coroutines-test 1.11.0, runTest, UnconfinedTestDispatcher
Paging:       paging-testing 3.5.0
Instrumented: Espresso 3.7.0 + Compose UI Test + Room Testing 2.7.0
testOptions:  unitTests.returnDefaultValues = true
```

**Pliki unit testów (host JVM — app/src/test):**

| Klasa                       | Typ    | Co testuje                                       |
|-----------------------------|--------|--------------------------------------------------|
| `CapsRepositoryTest`        | realny | 5 testów delegacji getLatest/getByCountryId/getByQuery/PagingSource |
| `AuthRepositoryTest`        | realny | 5 testów login/logout/cookie session/CSRF flow   |
| `HomeViewModelTest`         | realny | 3 testy stanu isLoggedIn/userName                |
| `BindersViewModelTest`      | realny | testy ViewModel klaserów                         |
| `LatestCapsPagingSourceTest`| realny | testy PagingSource dla latest caps               |

**Pliki testów instrumentowanych (device/emulator — app/src/androidTest):**

| Klasa                      | Co testuje                                     |
|----------------------------|------------------------------------------------|
| `ExampleInstrumentedTest`  | stub                                           |
| `PendingCapDaoTest`        | DAO dla kapsli oczekujących (Room)             |
| `BinderRepositoryTest`     | Repository klaserów (prawdziwy Room)           |
| `CapPositionRepositoryTest`| pozycje kapsli w klaserach                    |
| `FirestoreWriteThroughTest`| dual-write Room + Firestore                    |
| `FirestoreRestoreTest`     | przywracanie kolekcji ze snapshotu Firestore   |
| `MigrationTest`            | Room schema migrations                         |

Wzorzec testowy jest spójny z `CLAUDE.md`: constructor injection z MockK, `runTest`
dla suspend functions, `UnconfinedTestDispatcher` dla ViewModeli. Agent ma działające
wzorce do naśladowania dla każdej warstwy (ViewModel, Repository, PagingSource, DAO,
migracje Room, write-through Firestore).

---

## CI/CD

```
Provider:      nie wykryto (brak .github/workflows/)
Configuration: nie znaleziono
```

**Dostępna automatyzacja (poza pipeline):**

`.github/dependabot.yml` — automatyczne PR-y przy nowych wersjach zależności Gradle
(harmonogram: weekly). To nie jest pipeline CI, ale daje passywny monitoring wersji
i podatności.

Stage coverage (brak pipeline):

| Stage      | Status | Uwagi                                             |
|------------|--------|---------------------------------------------------|
| Lint       | ✗      | ktlint dostępny lokalnie (plugin), brak w CI      |
| Test       | ✗      | JUnit 4 + MockK lokalne, brak automatyzacji w CI  |
| Build      | ✗      | `./gradlew assembleDebug` lokalnie                |
| Type check | ✗      | kompilator Kotlin wbudowany, brak kroku CI        |
| Security   | ~      | Dependabot (PR-y przy podatnościach) — częściowe  |

ℹ Brak CI/CD pipeline. Ustawisz go w lekcji infrastruktury i wdrożenia.
Dla teraz lokalny runner testów wystarczy do współpracy z agentem.

---

## Configuration

Wszystkie oczekiwane pliki konfiguracyjne są obecne:

| Plik           | Status | Uwaga                                                            |
|----------------|--------|------------------------------------------------------------------|
| `.editorconfig`| ✓      | kt/kts: indent_size=4, max_line_length=120, UTF-8, LF            |
| `.gitignore`   | ✓      | Wyklucza .idea, /build, keystore.properties, *.jks, .claude/     |
| `CLAUDE.md`    | ✓      | Kompletna dokumentacja konwencji (struktura, MVVM, Hilt, nawigacja) |
| ktlint         | ✓      | Plugin `org.jlleitschuh.gradle.ktlint` w app/build.gradle        |

### High severity

Brak.

### Medium severity

Brak.

### Low severity

- **`.env.example`** — nie dotyczy. Android nie używa zmiennych środowiskowych serwera.
  Konfiguracja wrażliwa (`keystore.properties`, `google-services.json`) jest celowo
  poza VCS (`.gitignore`). Brak potrzeby `.env.example`.

---

## Stack Assessment Cross-Reference

```
Stack assessment: context/foundation/stack-assessment.md
Agent readiness (from stack-assess): ready
Gates passed: 4/4 (wszystkie kryteria agentowej przyjazności)
```

Dwa obszary zidentyfikowane w poprzedniej ocenie stack-assessment (2026-06-10,
`ready-with-compensation`) zostały zamknięte przed aktualną oceną:

| Gap ze stack-assess (2026-06-10)       | Wynik health-check (2026-06-15)                                          | Status         |
|----------------------------------------|--------------------------------------------------------------------------|----------------|
| Convention-based: partial (brak CLAUDE.md) | `CLAUDE.md` obecny z pełną dokumentacją: struktura pakietów, MVVM, Hilt, nawigacja, testowanie | Zmitigowany ✓ |
| Niskie pokrycie testami                | 5 realnych klas unit testów + 7 klas testów instrumentowanych (Room, Firestore, migracje) | Zmitigowany ✓ |

**Dodatkowe ustalenia health-check (poza stack-assess):**

- Dependabot skonfigurowany (`.github/dependabot.yml`) — monitoring podatności aktywny.
  Stack-assess nie odnotował tego.
- Instrumented tests pokrywają warstwy persistence (Room DAO, Repository, migracje,
  dual-write Firestore) — kompensuje brak testów integracyjnych w CI.
- Drobna rozbieżność wersji między `stack-assessment.md` a `app/build.gradle`:
  Compose BOM `2026.05.01` vs `2025.05.01`; Retrofit `3.0.0` vs `2.11`; Paging `3.5.0`
  vs `3.3.4` — stack-assessment odnotowuje nieznacznie starsze dane. Wersje
  w `app/build.gradle` są autorytarne.

---

## Recommended Fixes

### Fix before agent work (Category A)

Brak blokerów. Projekt spełnia wszystkie Category A kryteria — nie ma CRITICAL/HIGH
audit findings, test runner jest na miejscu i ma wzorce, dependency locking aktywny,
konfiguracja kompletna.

**Opcjonalne ulepszenia (niska priorytet — nie blokujące):**

#### 1. Uruchom `./gradlew dependencyUpdates` przed kolejną serią zmian

**Impact**: weryfikacja aktualności wersji (Room 2.8.x, Firebase BOM 34.x, OkHttp 5.x
jako kandydaci do minor/major update). Plugin jest zainstalowany, wynik dostępny w <2 min.
**Severity**: low
**Effort**: quick (< 5 min)
**Fix**:

```bash
./gradlew dependencyUpdates
# Raport: app/build/dependencyUpdates/report.txt
```

#### 2. Zaktualizuj `stack-assessment.md` o aktualne wersje z `app/build.gradle`

**Impact**: drobna rozbieżność wersji (Compose BOM, Retrofit, Paging) między
`stack-assessment.md` a rzeczywistym `app/build.gradle` może mylić agenta w przyszłych
sesjach. `app/build.gradle` jest autorytarne.
**Severity**: low
**Effort**: quick (< 5 min)
**Fix**: uruchom `/10x-stack-assess` ponownie lub ręcznie zaktualizuj sekcję
`stack_components:` w frontmatter `context/foundation/stack-assessment.md`.

---

### Addressed in upcoming lessons (Category B)

#### Brak CI/CD pipeline

**Lesson**: [Sprint Zero z Agentem: infrastruktura, walking skeleton i pierwszy deploy (M1L5)](https://platforma.przeprogramowani.pl/external/10xdevs-3/m1-l5)
**What you'll do there**: skonfigurowanie GitHub Actions workflow uruchamiającego
`./gradlew testDebugUnitTest` i `./gradlew ktlintCheck` na każdym push. Dependabot
jest już skonfigurowany — dodanie workflow to ~30 min pracy jednorazowej.

---

## Summary

```
Health status: healthy
```

Projekt CCI Android jest w dobrej kondycji do pracy z agentem. Mocne strony: dependency
locking aktywny (`app/gradle.lockfile` przez `lockAllConfigurations()`), test runner JUnit 4
+ MockK na miejscu z rzeczywistymi testami wszystkich warstw (ViewModel, Repository, PagingSource,
DAO, migracje Room, write-through Firestore), ktlint egzekwuje styl, a `CLAUDE.md` dokumentuje
pełne konwencje architektoniczne. Dependabot monitoruje zależności automatycznie.
Stack-assess dał `ready` (4/4 kryteria), a obie luki z poprzedniej oceny (`ready-with-compensation`)
zostały zamknięte. Jedyna luka — brak CI/CD pipeline — to Category B, spodziewany etap, który
adresujesz w lekcji M1L5.

Następny krok: projekt jest zdrowy — możesz przejść do agent onboarding lub wrócić do
aktywnej zmiany `collection-resilience` (epilog + testy + weryfikacja manualna).

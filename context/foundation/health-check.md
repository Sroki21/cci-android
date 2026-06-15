---
project: "CCI Android — wersja prywatna"
checked_at: 2026-06-15T08:50:00Z
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
recommended_fixes: 5
---

# Health Check — CCI Android

**Sprawdzono**: 2026-06-15 · **Commit**: b8b504c · **Branch**: main · **Repo**: Sroki21/cci-android
**Rodzina języka**: Kotlin/Android (JVM, Gradle) · **JDK użyty do weryfikacji**: Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`)

## Dependency Health

### Lockfile

```
Status: missing (brak gradle.lockfile)
Package manager: Gradle 9.5.1 (wrapper)
```

Gradle nie ma domyślnie włączonego dependency lockingu i nie wygenerowano `gradle.lockfile`. **Ryzyko jest jednak zmitigowane**: wszystkie zależności w `app/build.gradle` i `build.gradle` są przypięte do dokładnych wersji (brak dynamicznych `+` / `latest.release`), a wersje pluginów są jawne. Buildy są w praktyce reprodukowalne. Prawdziwy lockfile dodałby gwarancję dla zależności tranzytywnych.

Fix (opcjonalny): włącz `dependencyLocking { lockAllConfigurations() }` w `build.gradle` i wygeneruj lock przez `./gradlew dependencies --write-locks`.

### Security Audit

```
Tool: skipped — Gradle/Android nie ma wbudowanego narzędzia audytu CVE (jak Java/Dart)
Summary: 0 CRITICAL, 0 HIGH, 0 MODERATE, 0 LOW (nie skanowano pod kątem CVE)
Direct vs transitive: nie dotyczy (brak skanu)
Recommended external tool: GitHub Dependabot (alerts + auto-PR, zero konfiguracji dla repo na GitHub)
  lub OWASP dependency-check Gradle plugin (org.owasp.dependencycheck) dla skanu lokalnego/CI
```

Wszystkie zależności to biblioteki first-party Google/JetBrains lub de-facto standardy (Retrofit, Coil, OkHttp, MockK) — niski profil ryzyka, ale formalny skan CVE nie był wykonany.

### Outdated Dependencies

```
Packages with major version gaps (stabilne wydania): 0 w kategorii "2+ major behind"
```

Raport `./gradlew dependencyUpdates` (ben-manes 0.54.0) wykonany pomyślnie. Zdecydowana większość pozostających „nowszych" wersji to **pre-release (alpha/rc)** — nie rekomendowane do produkcji (np. material3 1.5.0-alpha21, compose-ui 1.12.0-alpha03, hilt 1.4.0-rc01, lifecycle 2.11.0-rc01, navigation 2.10.0-alpha05, appcompat 1.8.0-alpha01). Pomijamy je świadomie.

Stabilne wydania pozostające w tyle (informacyjnie, opcjonalna aktualizacja — żadne ≥2 major wstecz):

- **androidx.room** 2.7.0 → 2.8.4 (stabilny minor)
- **com.google.firebase:firebase-bom** 33.8.0 → 34.14.1 (1 major)
- **com.squareup.okhttp3:logging-interceptor** 4.12.0 → 5.4.0 (1 major)
- **com.pinterest.ktlint** (CLI rulesetu) 1.0.1 → 1.8.0 (narzędzie, znacząca poprawa reguł)
- **org.jetbrains.kotlin** 2.3.20 → 2.4.0 — **świadomie zablokowany**: czeka na KSP 2.4.x (zob. notatka projektowa o macierzy wersji)

## Test Suite

```
Test runner: JUnit 4 + MockK 1.14.11 + kotlinx-coroutines-test 1.11.0 + paging-testing 3.5.0
Tests found: 23 testy jednostkowe (6 klas) + 7 klas testów instrumentowanych
Test execution: passing (23/23 unit, 0 failures, 0 errors) — zweryfikowane realnym przebiegiem
```

```
Configuration: app/build.gradle (testOptions.unitTests.returnDefaultValues = true)
Framework: JUnit 4.13.2; mock przez MockK (constructor injection); korutyny przez runTest
```

Testy jednostkowe uruchomione przez `./gradlew testDebugUnitTest` — **BUILD SUCCESSFUL**, wszystkie zielone:

| Klasa testowa | Testy | Wynik |
|---|---|---|
| `data.AuthRepositoryTest` | 5 | ✓ |
| `data.CapsRepositoryTest` | 5 | ✓ |
| `data.LatestCapsPagingSourceTest` | 4 | ✓ |
| `ui.binders.BindersViewModelTest` | 5 | ✓ |
| `ui.home.HomeViewModelTest` | 3 | ✓ |
| `ExampleUnitTest` (stub) | 1 | ✓ |

Testy instrumentowane (`app/src/androidTest`, wymagają emulatora/urządzenia — **nieuruchamiane w tym health-checku**): `BinderRepositoryTest`, `CapPositionRepositoryTest`, `FirestoreRestoreTest`, `FirestoreWriteThroughTest`, `MigrationTest`, `PendingCapDaoTest`, `ExampleInstrumentedTest`.

To radykalna poprawa względem oceny stosu z 2026-06-10, gdzie istniały wyłącznie autogenerowane stuby. Agent ma teraz działający mechanizm weryfikacji własnych zmian oraz wzorce testowe do naśladowania (ViewModel, Repository, PagingSource, DAO, migracje Room, write-through Firestore).

## CI/CD

```
Provider: not detected
Configuration: not found (brak .github/workflows/, brak innej konfiguracji CI)
```

| Stage      | Status | Notes |
|------------|--------|-------|
| Lint       | ✗      | ktlint skonfigurowany lokalnie (plugin), ale brak pipeline CI |
| Test       | ✗      | runner działa lokalnie, ale brak pipeline CI |
| Build      | ✗      | nie skonfigurowane w CI |
| Type check | ✗      | kompilator Kotlin (wbudowany), brak osobnego kroku CI |
| Security   | ✗      | brak Dependabot/skanu w CI |

ℹ Brak konfiguracji CI/CD. To skonfigurujesz na lekcji o infrastrukturze i deploy ([Sprint Zero z Agentem — M1L5](https://platforma.przeprogramowani.pl/external/10xdevs-3/m1-l5)). Na ten moment działający lokalny runner testów wystarcza do współpracy z agentem.

## Configuration

### High severity

Brak luk wysokiej wagi. `.gitignore` obecny i poprawnie wyklucza sekrety (`keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, `/build`, `.claude/`).

### Medium severity

Brak luk średniej wagi. Formatter/linter (**ktlint** `org.jlleitschuh.gradle.ktlint` 12.1.2) jest skonfigurowany — `./gradlew ktlintCheck` przechodzi bez naruszeń. `.editorconfig` obecny i spójny z regułami ktlint (4 spacje, max line 120, LF, UTF-8).

### Low severity

- **`.env.example`** — brak, ale **nie dotyczy** tego stacku. Android nie używa plików `.env`; konfiguracja środowiskowa to `keystore.properties` (poza VCS) oraz `google-services.json` (Firebase). Dokumentacja zmiennych nie jest tu potrzebna.

Pozostałe oczekiwane pliki obecne: `.editorconfig` ✓, `.gitignore` ✓, `CLAUDE.md` ✓. `tsconfig` nie dotyczy (Kotlin jest statycznie typowany natywnie).

## Stack Assessment Cross-Reference

```
Stack assessment: context/foundation/stack-assessment.md
Agent readiness (from stack-assess): ready-with-compensation
```

Obie luki kompensacyjne ze stack-assessment zostały **wdrożone od czasu oceny (2026-06-10)**:

| Quality Gate Gap | Health-Check Finding | Status |
|------------------|----------------------|--------|
| convention_based: partial (brak pliku instrukcji) | `CLAUDE.md` obecny, z pełną sekcją konwencji architektonicznych (struktura pakietów, reguła podziału modeli, granice MVVM+Repository, wzorzec Hilt, nawigacja, testowanie) | Zmitigowane |
| test runner: brak realnych testów (tylko stuby) | 23 działające testy jednostkowe (ViewModel/Repository/PagingSource) + 7 klas testów instrumentowanych (Room, Firestore, migracje) | Zmitigowane |

Verdict `ready-with-compensation` ze stack-assess jest teraz w pełni pokryty — rekomendowane kompensacje istnieją w kodzie.

## Recommended Fixes

### Fix before agent work (Category A)

#### 1. Dodaj automatyczny skan podatności zależności (Dependabot)

**Impact**: Bez skanu CVE ani agent, ani Ty nie macie sygnału o znanych podatnościach w drzewie zależności (w tym tranzytywnych — Firebase, OkHttp, Retrofit ciągną sporo).
**Severity**: low
**Effort**: quick (< 5 min)
**Fix**: dodaj `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    schedule:
      interval: "weekly"
```

(Alternatywa lokalna/CI: OWASP dependency-check plugin `org.owasp.dependencycheck`, task `./gradlew dependencyCheckAnalyze`.)

#### 2. (Opcjonalnie) Włącz Gradle dependency locking

**Impact**: Przypięte wersje bezpośrednie już dają reprodukowalność, ale lock zamraża też zależności tranzytywne — agent może bezpiecznie rozumować o dokładnym stanie drzewa.
**Severity**: low
**Effort**: quick (< 5 min)
**Fix**: w `build.gradle` dodaj `dependencyLocking { lockAllConfigurations() }`, następnie `./gradlew dependencies --write-locks`.

#### 3. (Opcjonalnie) Zaktualizuj Room 2.7.0 → 2.8.4

**Impact**: Stabilny minor; projekt aktywnie używa Room (migracje, DAO, testy instrumentowane). Najnowszy patch poziom redukuje ryzyko znanych bugów.
**Severity**: low
**Effort**: quick (< 5 min)
**Fix**: zmień `def room_version = "2.7.0"` → `"2.8.4"` w `app/build.gradle`, przebuduj i uruchom testy instrumentowane Room.

#### 4. (Opcjonalnie) Zaktualizuj ktlint ruleset 1.0.1 → 1.8.0

**Impact**: Linter wymusza spójny styl kodu agenta; ruleset 1.0.1 jest mocno wstecz względem 1.8.0 (lepsze reguły, mniej fałszywych trafień).
**Severity**: low
**Effort**: moderate (15–30 min — może wymusić reformat)
**Fix**: ustaw wersję w bloku `ktlint { version = "1.8.0" }`, uruchom `./gradlew ktlintFormat`, przejrzyj diff.

#### 5. (Opcjonalnie) Rozważ Firebase BOM 33.8.0 → 34.14.1 i OkHttp 4.x → 5.x

**Impact**: Major bumpy z poprawkami i nowym API; nie pilne, ale warto zaplanować, bo z czasem rośnie dystans.
**Severity**: low
**Effort**: moderate (15–30 min — major bump, sprawdź changelog/breaking changes)
**Fix**: zaktualizuj wersje w `app/build.gradle`, przebuduj, przetestuj ścieżki auth/firestore i sieciowe.

### Addressed in upcoming lessons (Category B)

#### Brak pipeline CI/CD

**Lesson**: [Sprint Zero z Agentem: infrastruktura, walking skeleton i pierwszy deploy (M1L5)](https://platforma.przeprogramowani.pl/external/10xdevs-3/m1-l5)
**What you'll do there**: skonfigurujesz CI (np. GitHub Actions z krokami ktlint + testy + build) oraz pierwszy deploy. Na teraz lokalny runner wystarcza.

## Summary

```
Health status: healthy
```

Projekt CCI Android jest w **dobrej kondycji do pracy z agentem**. Mocne strony: działający i zielony zestaw 23 testów jednostkowych (plus 7 klas testów instrumentowanych), skonfigurowany formatter/linter (ktlint przechodzi bez naruszeń), `CLAUDE.md` z pełnymi konwencjami architektonicznymi, oraz `.gitignore` chroniący sekrety — obie luki kompensacyjne ze stack-assessment zostały realnie wdrożone. Pozostałe braki są drobne i niskiej wagi: brak formalnego skanu CVE (rekomendacja: Dodaj Dependabot), brak prawdziwego lockfile (zmitigowane przypiętymi wersjami), oraz kilka opcjonalnych aktualizacji stabilnych wydań — żadnego CRITICAL/HIGH, żadnej luki blokującej. Brak CI to oczekiwany etap (Category B, M1L5).

Next step: projekt jest zdrowy — możesz przejść do agent onboarding. Opcjonalnie wdroż szybki fix #1 (Dependabot, <5 min) przed dalszą pracą.

<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Sentry Monitoring — crash reporting i schwytane błędy

- **Plan**: context/changes/sentry-monitoring/plan.md
- **Scope**: Phase 1 of 1 (full plan review)
- **Date**: 2026-06-15
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension            | Verdict |
|----------------------|---------|
| Plan Adherence       | PASS    |
| Scope Discipline     | PASS    |
| Safety & Quality     | WARNING |
| Architecture         | WARNING |
| Pattern Consistency  | WARNING |
| Success Criteria     | PASS    |

## Findings

### F1 — SentryAndroid.init wywołany przed super.onCreate()

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture
- **Location**: CCIApplication.kt:25
- **Detail**: Plan uzasadniał kolejność "przed super.onCreate()" troską o pola @Inject (Hilt inject), ale wszystkie @Inject fields są używane wyłącznie wewnątrz applicationScope.launch{} — który startuje po super. Brak faktycznej zależności. SDK Sentry dokumentuje init wewnątrz onCreate(), nie przed super.
- **Fix A ⭐ Recommended**: Przenieś init za super.onCreate() — super.onCreate() → SentryAndroid.init() → launch {}
  - Strength: Zgodne z oficjalną dokumentacją SDK; @Inject fields nadal bezpieczne.
  - Tradeoff: Traci się najwcześniejsze okno crash-capture (~20ms), bez praktycznego wpływu.
  - Confidence: HIGH — @Inject fields wyłącznie w korutynie.
  - Blind spot: Żaden.
- **Fix B**: Zachowaj kolejność, dodaj komentarz z uzasadnieniem ryzyka.
  - Strength: Zero ryzyka zmiany.
  - Tradeoff: Ryzyko regresji przy upgrade SDK pozostaje.
  - Confidence: MED.
  - Blind spot: Nie sprawdzono changelog Sentry 7.x.
- **Decision**: FIXED via Fix A

### F2 — Dynamiczny zakres wersji 7.+ zamiast pinowanej wersji

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: app/build.gradle:113
- **Detail**: Wszystkie inne zależności w projekcie są pinowane do dokładnych wersji (hilt 2.59.2, room 2.7.0, coil 2.7.0 itd.). 7.+ jest niespójne z konwencją projektu i może cicho wyresolvować nową wersję przy kolejnym --write-locks.
- **Fix**: Zamień `"io.sentry:sentry-android:7.+"` na `"io.sentry:sentry-android:7.22.6"` — pinuj do wersji z lockfile.
- **Decision**: FIXED

### F3 — restoreIfEmpty() w login() połyka wyjątek bez captureException

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: AuthRepository.kt:58
- **Detail**: `try { firestoreRestoreUseCase.restoreIfEmpty() } catch (_: Exception) {}` — trzecie miejsce z połykanym wyjątkiem. Plan nie obejmował tego miejsca explicite, ale CCIApplication.kt ma identyczny wzorzec z captureException.
- **Fix**: Dodaj `Sentry.captureException(e)` + `Log.w` w catch bloku — identyczny wzorzec jak CCIApplication.kt:36.
- **Decision**: FIXED

### F4 — DSN Sentry commitowany w plaintext

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: app/src/main/AndroidManifest.xml:20
- **Detail**: DSN widoczny w repozytorium. Plan zakwalifikował to jako akceptowalne dla prywatnej 1-osobowej aplikacji. Ingest EU (.de.sentry.io) — GDPR-świadomy wybór. Notatka na wypadek open-source'owania: zrotować DSN i przenieść do BuildConfig + CI secret.
- **Fix**: Brak wymaganego działania przy obecnym stanie.
- **Decision**: SKIPPED — ryzyko zaakceptowane dla prywatnego repo

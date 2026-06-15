---
project: "CCI Android — wersja prywatna"
assessed_at: 2026-06-15T00:00:00Z
agent_readiness: ready
context_type: brownfield
stack_components:
  language: Kotlin 2.3.20
  framework: Jetpack Compose BOM 2025.05.01 + MVVM + Repository
  di: Hilt 2.59.2 (KSP 2.3.9)
  persistence: Room (lokalna DB) + Firebase Firestore (cloud sync)
  networking: Retrofit 2.11 + kotlinx.serialization 1.7.3
  paging: Paging 3.3.4
  build_tool: Gradle (Groovy DSL) + AGP 9.2.1
  test_runner: JUnit 4 + MockK + Espresso + Compose UI Test
  linting: ktlint 12.1.2
  package_manager: Gradle z dependency locking
  ci_provider: null
  deployment_target: Android APK (minSdk 24 / targetSdk 37)
gates_passed: 4
gates_partial: 0
gates_failed: 0
---

## Stack Components

**Język — Kotlin 2.3.20 (JVM 21)**: statycznie typowany język JVM będący pierwszorzędnym językiem Android. Projekt używa Kotlin 2.3.20 z JVM toolchain 21. Kompilacja z pluginami: `kotlin.plugin.compose`, `kotlin.plugin.serialization`, `ksp`. Modele domenowe to `data class` z adnotacjami `@Serializable` (kotlinx.serialization 1.7.3). Poprzednia wersja: 2.1.20 (zaktualizowana od oceny z 2026-06-10).

**Framework/UI — Jetpack Compose BOM 2025.05.01**: Google's deklaratywny framework UI dla Android. Projekt używa Compose Material 3, Navigation Compose 2.9 (type-safe trasy przez `Screen.kt`), Coil 2.7 (asynchroniczne ładowanie obrazów). Architektura MVVM z warstwą Repository; każdy ekran ma swój ViewModel (`hiltViewModel()`) i opcjonalny PagingSource.

**DI — Hilt 2.59.2 (KSP 2.3.9)**: standardowy framework wstrzykiwania zależności dla Android. KSP (Kotlin Symbol Processing) zamiast KAPT — nowoczesny, szybszy procesor adnotacji. Moduły DI w pakiecie `di/`, nazwane `<Domain>Module.kt`. Poprzednia wersja Hilt: 2.51.1.

**Persistence — Room + Firebase Firestore**: Room jako lokalny offline-first store (schemat v7 po implementacji collection-resilience); Firestore jako cloud backup/sync dla klaserów, stron i pozycji kapsli. Dual-write pattern: zapis do Room + Firestore jednocześnie, odczyt zawsze z Room.

**Build tool — Gradle (Groovy DSL) + AGP 9.2.1**: pliki `.gradle` (Groovy DSL). AGP 9.2.1 to najnowsza major wersja (wymaga JVM 21). Dependency locking aktywne (`lockAllConfigurations()`). ktlint 12.1.2 i versions plugin 0.54.0 dołączone.

**Test runner — JUnit 4 + MockK + Espresso + Compose UI Test**: framework testowy na miejscu; MockK (kotlin-friendly mocking library) dodany do zależności testowych. Wzorzec testowania udokumentowany w CLAUDE.md.

**Instruction files — CLAUDE.md (root projektu)**: plik dokumentuje konwencje architektoniczne: strukturę pakietów, regułę podziału modeli, granice MVVM+Repository, wzorzec Hilt DI, nawigację i testowanie. Kompensuje brak wymuszania konwencji przez framework (Android nie jest Rails-style convention-over-configuration).

**CI/CD**: nie wykryto (brak `.github/workflows/`, brak konfiguracji CI).

## Quality Gate Assessment

| Komponent                    | Typed | Convention | Training Data | Documented | Verdict      |
|------------------------------|-------|------------|---------------|------------|--------------|
| Język (Kotlin 2.3.20)        |  ✓    |     —      |      —        |     —      | pass         |
| Framework (Compose+MVVM)     |  —    |     ✓      |      ✓        |     ✓      | pass         |
| Build tool (Gradle+AGP 9.2)  |  —    |     ✓      |      ✓        |     ✓      | pass         |
| Test runner (JUnit4+MockK)   |  —    |     —      |      ✓        |     ✓      | pass         |

### Gate Details

#### Type safety — PASS

Kotlin jest statycznie typowany z natury — brak opcjonalności typów bez explicitnego `?`. Modele domenowe (`Cap`, `CapExtended`, `Country`, encje Room itp.) to `data class` z jawnie typowanymi polami. kotlinx.serialization wymusza kontrakt typów na granicy API. Hilt + KSP generuje type-safe klasy DI.

Dowód: `build.gradle` — `id 'org.jetbrains.kotlin.plugin.compose' version '2.3.20'`; `model/Cap.kt` — `@Serializable data class Cap(val id: Int, ...)`.

#### Convention-based — PASS *(upgrade od partial — 2026-06-10)*

Android/Jetpack ma silne konwencje ekosystemowe (Google Architecture Guides), a projekt ich przestrzega. Od poprzedniej oceny projekt ma `CLAUDE.md` w katalogu głównym, który dokumentuje wszystkie konwencje:
- Jedna aktywność (`MainActivity`) + NavHost + trasy w `Screen.kt`
- ViewModel per ekran, Repository per domena
- PagingSource tworzony przez Repository (nie ViewModel)
- `di/` dla modułów Hilt, `<Domain>Module.kt`
- `ui/<feature>/<Feature>Screen.kt` + `<Feature>ViewModel.kt`
- Reguła podziału modeli: `model/` domenowe, `data/model/` wewnętrzne

Jedna pre-existing niespójność: `data/model/Country.kt` jest używany przez UI przez `CountriesRepository`, co formalnie narusza regułę `data/model/` = only internal. Udokumentowane jako znany wyjątek; nie blokuje agenta.

Dowód: `CLAUDE.md` w root projektu dokumentuje wszystkie konwencje; struktura `app/src/main/java/pl/sroki/cci/android/` zgodna z dokumentowanym wzorcem.

#### Popular in training data — PASS

Kotlin + Jetpack Compose + Hilt + MVVM + Retrofit + Paging 3 + Room = **kanoniczny nowoczesny stos Android**. To dokładnie to, co Google rekomenduje i co dominuje w dokumentacji Android, Kotlin Playground, tutorialach i Stack Overflow od 2021. Firebase Firestore to standardowy wybór dla Android sync/backup. Ocena wewnątrz rodziny Android/Kotlin — nie globalnie.

Dowód: `app/build.gradle` — wszystkie zależności to Google/JetBrains first-party lub de-facto standard (Retrofit Square, Coil, MockK).

#### Well-documented — PASS

Wszystkie komponenty stosu mają aktualną, wersjonowaną oficjalną dokumentację:
- Kotlin 2.3: kotlinlang.org (wersjonowane API docs)
- Jetpack Compose: developer.android.com (BOM-based, per-component)
- Hilt: developer.android.com/training/dependency-injection/hilt-android
- Room: developer.android.com/training/data-storage/room
- Firestore: firebase.google.com/docs/firestore (Android SDK)
- Navigation Compose: developer.android.com/guide/navigation/navigation-compose
- Retrofit: square.github.io/retrofit/
- Paging 3: developer.android.com/topic/libraries/architecture/paging/v3-overview
- Coil: coil-kt.github.io/coil/
- MockK: mockk.io

## Gaps & Compensation

Wszystkie 4 kryteria przechodzą — brak luk blokujących pracę agenta. Dwa obszary poprawy odnotowane dla `/10x-health-check`:

### Obszar 1: Brak CI/CD

**Stan**: żadna automatyzacja CI nie jest skonfigurowana. Testy przechodzą lokalnie (`./gradlew testDebugUnitTest`), ktlint działa lokalnie, ale brak weryfikacji na push/PR.

**Wpływ na agenta**: agent nie ma zewnętrznego sygnału "testy przeszły po moich zmianach" — musi polegać na lokalnym uruchomieniu. Nie blokuje pracy, ale zwiększa ryzyko regresu przy dłuższych sesjach.

**Kompensacja**: dodanie GitHub Actions jest jednorazowym zadaniem (~30 minut). Kandydat do pierwszej iteracji `/10x-health-check`.

### Obszar 2: Niskie pokrycie testami

**Stan**: MockK i framework testowy są na miejscu; wzorzec testowania udokumentowany w CLAUDE.md. Jednak faktyczne pokrycie testami jest niskie — głównie autogenerated stubs z poprzedniej oceny, plus selektywne testy dodane przy konkretnych zmianach.

**Wpływ na agenta**: przy dodawaniu nowych funkcji agent nie ma wzorców testowych do naśladowania poza CLAUDE.md (który opisuje *jak* testować, ale nie *co* testować).

**Kompensacja**: priorytetyzacja testów ViewModeli i Repository dla nowych funkcji. Nie wymaga zmian w CLAUDE.md — wzorzec jest udokumentowany.

### Recommended Instruction File Additions

Brak nowych wpisów wymaganych — CLAUDE.md jest aktualny i kompletny. Jedyna ewentualna aktualizacja przy dodaniu CI:

```markdown
## CI/CD

Po konfiguracji GitHub Actions:
- `./gradlew testDebugUnitTest` — uruchamiane na każdym push
- `./gradlew ktlintCheck` — linting na każdym push
- Brak testów instrumentowanych w CI (wymagają urządzenia/emulatora)
```

## Summary

Stos oceniony jako **ready** — wszystkie 4 kryteria agentowej przyjazności przechodzą.

**Upgrade od poprzedniej oceny (2026-06-10, `ready-with-compensation`):** Obecność `CLAUDE.md` z pełną dokumentacją konwencji architektonicznych zamknęła lukę Convention-based (partial → pass). Dodanie Room, Firestore, MockK i dependency locking wzmocniło stos bez wpływu na ocenę gate'ów.

**Mocne strony:**
- Kotlin — statyczna typizacja eliminuje całą klasę błędów agenta
- CLAUDE.md dokumentuje konwencje — agent nie musi zgadywać gdzie co umieścić
- Kanoniczny stos Android — agent zna idiomy, dokumentacja jest aktualna
- Room + Firestore dual-write — sprawdzony wzorzec, dobrze opisany w training data

**Obszary do zaadresowania (nie blokujące):**
1. CI/CD brak — jednorazowe zadanie dla `/10x-health-check`
2. Pokrycie testami niskie — stopniowa poprawa przy nowych funkcjach

Następny krok: `/10x-health-check`

# CI/CD Pipeline — Plan Brief

> Full plan: `context/changes/ci-cd/plan.md`

## What & Why

Dodanie GitHub Actions workflow uruchamiającego testy jednostkowe i ktlint na każdy push
do `main`. Brak CI był jedyną luką Category B z `health-check` — projekt ma już działający
test runner i linter, CI to tylko ich automatyzacja.

## Starting Point

`.github/dependabot.yml` istnieje (weekly Gradle updates), ale brak `workflows/`. Testy
i ktlint działają lokalnie; nie ma żadnego sygnału "testy przeszły po tym push".
`google-services.json` jest w repo → brak potrzeby zarządzania sekretami.

## Desired End State

Po każdym push do `main` GitHub uruchamia job: checkout → JDK 21 → cache → unit testy →
ktlint. Zielona odznaka = `main` jest zdrowy. Czas buildu ~2-3 min po pierwszym (cache).

## Key Decisions Made

| Decyzja             | Wybór                         | Dlaczego                                                        |
|---------------------|-------------------------------|-----------------------------------------------------------------|
| Trigger             | Push do `main`                | Projekt jednodev — prosty sygnał, zero szumu                   |
| Kroki               | testDebugUnitTest + ktlintCheck | Weryfikuje to, co działa lokalnie; bez buildu APK (szybciej)  |
| Cache               | setup-java@v4 z `cache: gradle` | Jeden krok zamiast osobnego actions/cache; klucz z lockfile    |
| Fail policy         | Fail fast (domyślne)           | Pierwszy błąd zatrzymuje job — czytelny raport w GitHub UI     |
| google-services.json | W repo (nie w .gitignore)     | Brak secret management — CI nie potrzebuje żadnej konfiguracji |

## Scope

**In scope:** `.github/workflows/android.yml` — jeden plik, jedna faza.

**Out of scope:** testy instrumentowane (emulator), release build/signing, deploy APK,
triggery na PR lub inne gałęzie.

## Architecture / Approach

```
push → main
  └─ job: ci (ubuntu-latest)
       ├─ checkout@v4
       ├─ setup-java@v4  (JDK 21 temurin, cache: gradle)
       ├─ chmod +x gradlew
       ├─ ./gradlew testDebugUnitTest
       └─ ./gradlew ktlintCheck
```

## Phases at a Glance

| Faza               | Co dostarcza                              | Ryzyko                                      |
|--------------------|-------------------------------------------|---------------------------------------------|
| 1. Workflow        | `.github/workflows/android.yml` + zielony CI | Gradle toolchain może nie znaleźć JDK 21 automatycznie (mało prawdopodobne z setup-java) |

**Prerequisites:** dostęp do GitHub repo, uprawnienia do tworzenia Actions.
**Estimated effort:** ~15 min (jeden plik, jeden push, weryfikacja w GitHub UI).

## Open Risks & Assumptions

- Gradle toolchain auto-detection zakłada, że `JAVA_HOME` ustawiony przez `setup-java`
  zostanie podniesiony przez `jvmToolchain(21)` — standardowe zachowanie, ale pierwsze
  uruchomienie zweryfikuje.
- `lockAllConfigurations()` wymuszone przez `subprojects {}` — jeśli lockfile jest
  niekompletny dla konfiguracji używanej przez test runner na CI, build padnie z
  "dependency lock state incompatible". Mało prawdopodobne (lockfile tworzony lokalnie
  obejmuje wszystkie konfiguracje).

## Success Criteria (Summary)

- Job `ci` zielony na GitHub Actions dla push do `main`
- `23 tests, 0 failures` + `BUILD SUCCESSFUL` w logach
- Cache hit przy drugim uruchomieniu (czas ~2-3 min)

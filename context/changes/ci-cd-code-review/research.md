---
date: 2026-06-16T12:00:00+02:00
researcher: Sroki
git_commit: 510fea277ce2c5fb46e11dba83c434bac7a54458
branch: main
repository: cci-android
topic: "CI/CD workflow for PR code reviews — architecture research"
tags: [research, github-actions, claude-sdk, code-review, composite-action]
status: complete
last_updated: 2026-06-16
last_updated_by: Sroki
---

# Research: CI/CD workflow for PR code reviews

**Date**: 2026-06-16T12:00:00+02:00
**Researcher**: Sroki
**Git Commit**: `510fea277ce2c5fb46e11dba83c434bac7a54458`
**Branch**: main
**Repository**: cci-android

## Research Question

Zaprojektowanie architektury CI/CD dla AI code review każdego nowego PR do master, na
podstawie wymagań w `agent/requirements.md`.

## Summary

Istniejąca infrastruktura GHA (`android.yml`) pokrywa push→main z testami i lintem —
nie wymaga zmian. Nowa warstwa code review to oddzielny workflow + composite action
wywoływany tylko na PR. Kluczowe decyzje: użyć `@anthropic-ai/sdk` (REST client, nie
Agent SDK) w skrypcie review, etykiety tworzene raz przez skrypt inicjalizacyjny,
trigger retry przez label `ai-cr:review`. Jedyna blocker przed `/10x-plan`: brak
definicji kryteriów oceny — placeholder `{{CR_CRITERIA}}` musi zostać wypełniony.

## Detailed Findings

### Istniejąca infrastruktura GHA

- `.github/workflows/android.yml` — aktywny; trigger: `push: branches: [main]`; kroki:
  checkout → setup-java@v4 (JDK 21 temurin, cache gradle) → chmod → testDebugUnitTest
  → ktlintCheck. **Nie wymaga zmian.**
- `.github/dependabot.yml` — weekly Gradle updates; brak `actions` section.
- `.github/actions/` — **nie istnieje** → composite action dopiero do stworzenia.
- Brak jakichkolwiek secrets w repozytorium poza wbudowanym `GITHUB_TOKEN`.

Źródło: `.github/workflows/android.yml:1-31`, `.github/dependabot.yml:1-5`.

### Agent SDK — decyzja dotycząca implementacji skryptu

W `agent/` jest zainstalowany `@anthropic-ai/claude-agent-sdk@0.3.178` (zod v4, tsx 4.x).
SDK ten opakowuje binary Claude Code CLI jako subprocess — wymaga zainstalowanego `claude`
CLI na runnerze i osobnej autentykacji. Dla CI nie jest to właściwy wybór.

**Zalecenie**: skrypt review (`agent/src/review.ts`) używa `@anthropic-ai/sdk` (REST
client), nie Agent SDK. Uzasadnienie:

| Kryterium              | `@anthropic-ai/claude-agent-sdk`     | `@anthropic-ai/sdk`              |
|------------------------|--------------------------------------|----------------------------------|
| Zależność binarna      | tak — `claude` CLI musi być na PATH  | nie — tylko HTTP                 |
| Autentykacja CI        | API key + cli setup (złożone)        | `ANTHROPIC_API_KEY` env (proste) |
| Latencja               | wyższa (subprocess spawn + warmup)   | niższa (bezpośrednie HTTP)       |
| Odpowiedni dla         | agentic/interactive (index.ts)       | structured prompt→response (CI)  |
| Streaming              | opcjonalne, przez `for await`        | opcjonalne, przez `streamText`   |

`agent/src/index.ts` (istniejący) — pozostaje niezmieniony, używa Agent SDK do
interaktywnej eksploracji codebase. `agent/src/review.ts` (nowy) — używa REST SDK
do jednorazowego wywołania review.

### Struktura plików docelowych

```
.github/
  workflows/
    android.yml                          (istniejący — bez zmian)
    code-review.yml                      (NOWY — workflow główny)
  actions/
    ai-code-review/
      action.yml                         (NOWY — composite action)
agent/
  src/
    index.ts                             (istniejący — bez zmian)
    review.ts                            (NOWY — skrypt review dla CI)
  package.json                           (dodać @anthropic-ai/sdk)
```

### GHA trigger — mechanizm i retry

Trigger na `pull_request` z typami `opened`, `synchronize`, `reopened` uruchamia
review automatycznie. Retry przez label:

```yaml
on:
  pull_request:
    types: [opened, synchronize, reopened, labeled]
    branches: [master]
```

W jobie dodać warunek, żeby filtrować label-events do tylko `ai-cr:review`:

```yaml
jobs:
  code-review:
    if: >
      github.event.action != 'labeled' ||
      github.event.label.name == 'ai-cr:review'
```

Po zakończeniu review skrypt usuwa etykietę `ai-cr:review` przez `gh pr edit
--remove-label "ai-cr:review"`, żeby uniknąć nieskończonej pętli.

### Permissions i secrets

| Zasób                | Źródło           | Potrzebne akcje                          |
|----------------------|------------------|------------------------------------------|
| `GITHUB_TOKEN`       | wbudowany w GHA  | `pull-requests: write` (komentarz + label) |
| `ANTHROPIC_API_KEY`  | repo secret (nowy) | tylko odczyt w env                      |
| `contents: read`     | wbudowany        | checkout + git diff                      |

Blok permissions w workflowie:

```yaml
permissions:
  pull-requests: write
  contents: read
```

### Etykiety — cykl życia

Trzy etykiety do stworzenia raz (manualnie lub przez skrypt bootstrap):

| Etykieta       | Kolor     | Cel                                    |
|----------------|-----------|----------------------------------------|
| `ai-cr:review` | `#6366f1` | Trigger retry — usuwana po reviewie    |
| `ai-cr:passed` | `#16a34a` | Review zaliczony                       |
| `ai-cr:failed` | `#e11d48` | Review niezaliczony                    |

Przed dodaniem `ai-cr:passed` lub `ai-cr:failed` skrypt usuwa poprzednią etykietę
(o ile istnieje), żeby PR miał zawsze dokładnie jedną etykietę oceny.

### Input: PR description — tradeoff kosztowy

Requirements oznaczają PR description jako `?? cost tradeoff`. Analiza:

- Typowy diff Android Kotlin (zmiana 1-3 plików): 500–3000 tokenów
- PR title: 10–30 tokenów
- PR description: 50–500 tokenów (przy normalnym użyciu)
- Łączny input: zazwyczaj <5000 tokenów → koszt na Sonnet 4.6: ~$0.015

**Zalecenie**: włączyć PR description domyślnie, z truncate do 1000 znaków.
Marginalne koszty nie uzasadniają wykluczenia wartościowego kontekstu.

### Architektura composite action — dane wejściowe i wyjściowe

```yaml
# .github/actions/ai-code-review/action.yml
inputs:
  pr_title:
    required: true
  pr_description:
    required: false
    default: ''
  github_token:
    required: true
  anthropic_api_key:
    required: true
outputs:
  review_passed:
    description: 'true | false'
  review_summary:
    description: 'Markdown summary for PR comment'
```

Composite action wykonuje kroki:
1. Setup Node.js 20
2. `npm ci` w `agent/`
3. `node agent/src/review.ts` (z git diff wygenerowanym w workflowie)
4. Odczyt outputs → post PR comment → set labels

### Scoring i próg pass/fail

Requirements: każde kryterium 1–10 (1 = najgorszy, 10 = najlepszy).
Próg pass/fail **nie jest zdefiniowany** w requirements → open question.
Propozycja do decyzji: `passed` gdy wszystkie kryteria ≥ 5 ORAZ średnia ≥ 6.

## Code References

- `.github/workflows/android.yml:1-31` — istniejący CI, wzorzec do naśladowania
- `.github/dependabot.yml:1-5` — brak `actions` ecosystem entry (można dodać)
- `agent/package.json` — dodać `"@anthropic-ai/sdk": "^0.104.0"` do dependencies
- `agent/src/index.ts:1-35` — wzorzec użycia Agent SDK (NIE do powielania w review.ts)

## Architecture Insights

**Separacja odpowiedzialności**: dwa skrypty w `agent/src/` z różnymi SDK — `index.ts`
(Agent SDK, interaktywny) i `review.ts` (REST SDK, jednorazowy) — to właściwy podział.
Composite action izoluje workflowy od szczegółów implementacji, co spełnia wymaganie
"main workflow easy to reason about".

**Unikanie `pull_request_target`**: dla prywatnego repo z jednym deweloperem `pull_request`
wystarczy (ma dostęp do secrets). `pull_request_target` ma inne implikacje bezpieczeństwa
i nie jest tu potrzebny.

**Idempotentność retry**: klucz do poprawności — przed dodaniem nowej etykiety usunąć
starą `ai-cr:passed`/`ai-cr:failed`; po zakończeniu usunąć `ai-cr:review`.

## Historical Context

- `context/archive/2026-06-15-ci-cd/plan.md` — pierwsza iteracja CI: workflow na push do
  main z testami + ktlint. Wzorzec setup-java@v4 z `cache: 'gradle'`, `chmod +x gradlew`.
  Decyzja "tylko push do main, nie PR" — świadomie wykluczona (status: done).
- `context/archive/2026-06-15-ci-cd/plan-brief.md` — key decisions: trigger, kroki, cache
  policy. Fail-fast domyślne.
- `context/foundation/stack-assessment.md:17` — `ci_provider: null` — nieaktualne po
  wdrożeniu `2026-06-15-ci-cd`. Pole do aktualizacji po tym change.

## Related Research

- `context/changes/refactor-opportunities/research.md` — badanie struktury kodu (inne domeny)
- `context/map/repo-map.md` — mapa repozytorium (referencja do struktury projektu)

## Open Questions

1. **`{{CR_CRITERIA}}` — BLOCKER**: kryteria oceny nie są zdefiniowane. To jedyna blokada
   przed `/10x-plan`. Konieczna decyzja przed planowaniem. Propozycja kryteriów dla
   projektu Android/Kotlin:
   - **Zgodność z CLAUDE.md** — nazewnictwo pakietów, podział modeli, granice MVVM
   - **Izolacja warstw** — brak Retrofit w ViewModel, brak Room @Entity w UI
   - **Kotlin idiomy** — null safety, coroutines, sealed classes, data classes
   - **Pokrycie testami** — nowa logika ViewModel/Repository powinna mieć testy
   - **Czytelność** — długość metod, single responsibility, nazwy zmiennych

2. **Próg pass/fail**: nie zdefiniowany w requirements. Propozycja: wszystkie ≥ 5 i
   średnia ≥ 6 → `passed`; inaczej `failed`.

3. **Model Claude**: requirements nie wskazują modelu. Propozycja: `claude-sonnet-4-6`
   dla balance kosztu/jakości (code review nie wymaga Opus-level reasoning).

4. **Granulacja diffa**: diff całego PR vs diff per-plik? Całość jest prostsza; per-plik
   umożliwia bardziej celny feedback. Propozycja: cały diff w jednym wywołaniu.

# CI/CD Pipeline — AI Code Review (CCI Android)

**Repozytorium:** https://github.com/Sroki21/cci-android  
**Moduł:** M5L2 (pierwszy Agent zespołowy) · M5L3 (Code Review w erze AI)

---

## 1. Architektura pipeline'u

```
Pull Request (opened / synchronize / labeled: ai-cr:review)
        │
        ▼
┌─────────────────────────────────────────────┐
│  GitHub Actions Workflow: AI Code Review    │
│  .github/workflows/pr-review.yml            │
│                                             │
│  Job: ai-review (ubuntu-latest)             │
│  ├── Step 1: Checkout (fetch-depth: 0)      │
│  └── Step 2: Run AI review (composite)      │
│       ├── Collect filtered diff             │
│       │   (*.kt, *.gradle → /tmp/…)         │
│       ├── Setup Node.js 20                  │
│       ├── npm install                       │
│       └── node index.js                     │
│            ├── Anthropic API (claude-haiku) │
│            ├── Post/update PR comment       │
│            └── Swap labels (passed/failed)  │
└─────────────────────────────────────────────┘
```

**Trigger:** każdy PR do `main` (opened/synchronize). Retry: label `ai-cr:review`.  
**Model:** `claude-haiku-4-5` — wybrany na podstawie porównania modeli (sekcja 5).

---

## 2. Workflow YAML

```yaml
# .github/workflows/pr-review.yml
name: AI Code Review

on:
  pull_request:
    types: [opened, synchronize, labeled]
    branches: [main]

jobs:
  ai-review:
    name: AI Code Review
    runs-on: ubuntu-latest
    if: github.event.action != 'labeled' || github.event.label.name == 'ai-cr:review'
    permissions:
      pull-requests: write
      contents: read

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Run AI review
        uses: ./.github/actions/ai-review
        with:
          github-token: ${{ secrets.GITHUB_TOKEN }}
          anthropic-api-key: ${{ secrets.ANTHROPIC_API_KEY }}
          pr-number: ${{ github.event.pull_request.number }}
          pr-title: ${{ github.event.pull_request.title }}
          pr-body: ${{ github.event.pull_request.body }}
          base-ref: ${{ github.base_ref }}
```

---

## 3. Widok pipeline'u i job

**Run ID:** `27637719786`  
**Trigger:** `pull_request` na branchu `test/ai-review-e2e`  
**Commit:** `22625ed` — *test(ai-review): minimal .kt change to trigger AI review E2E*  
**Status:** ✅ success  
**Czas całkowity: 19 s**

**URL Actions:**  
https://github.com/Sroki21/cci-android/actions/runs/27637719786

**URL joba `AI Code Review`:**  
https://github.com/Sroki21/cci-android/actions/runs/27637719786/job/81756106092

### Kroki joba

| Krok | Czas | Status |
|------|------|--------|
| Set up job | 1 s | ✅ success |
| Checkout | 1 s | ✅ success |
| **Run AI review** | **13 s** | ✅ success |
| Post Run AI review | 0 s | ✅ success |
| Post Checkout | 1 s | ✅ success |
| Complete job | 0 s | ✅ success |

---

## 4. Logi z pipeline'u

Logi dostępne pod:  
https://github.com/Sroki21/cci-android/actions/runs/27637719786/job/81756106092

Logi kroku **Run AI review** (`.github/actions/ai-review`):

```
Prepare all required actions
Getting action download info
Download action repository 'actions/setup-node@v4' (SHA:49933ea5288caeca8642d1e84afbd3f7d6820020)
Run ./.github/actions/ai-review
Run git -C "$GITHUB_WORKSPACE" diff "origin/$BASE_REF"...HEAD -- '*.kt' '*.gradle' > /tmp/review_diff.txt
Run actions/setup-node@v4
Attempting to download 20...
Acquiring 20.20.2 - x64 from https://github.com/actions/node-versions/releases/download/20.20.2-23521894959/node-20.20.2-linux-x64.tar.gz
Extracting ...
/usr/bin/tar xz --strip 1 --warning=no-unknown-keyword --overwrite -C /home/runner/work/_temp/163668ad-7d4d-49e8-89c2-449728a5dfea -f /home/runner/work/_temp/4e9fb60f-8b18-4fcf-9de2-84c93b18545d
Adding to the cache ...
Environment details
Run npm install
npm warn deprecated node-domexception@1.0.0: Use your platform's native DOMException instead

added 37 packages, and audited 38 packages in 2s

6 packages are looking for funding
  run `npm fund` for details

found 0 vulnerabilities
Run node index.js
Calling Anthropic API...
Verdict: passed
Review posted successfully.
```

Composite action wykonuje 4 skryptowe kroki:
1. `git diff origin/main...HEAD -- '*.kt' '*.gradle'` → filtrowany diff do `/tmp/review_diff.txt`
2. `actions/setup-node@v4` → node 20.20.2 (pobrany z cache)
3. `npm install` → 37 pakietów, 0 podatności
4. `node index.js` → Anthropic API → `Verdict: passed` → komentarz na PR + labele

---

## 5. Komentarz LLM na PR #6

**PR:** [test: AI review E2E - Category.displayName](https://github.com/Sroki21/cci-android/pull/6)  
**Branch:** `test/ai-review-e2e → main`  
**Label po review:** `ai-cr:passed`

Treść komentarza opublikowanego przez pipeline:

---

> ## ✅ AI Review: PASSED
>
> | Criterion | Score | Status |
> |-----------|-------|--------|
> | Implementation Correctness | 9/10 | ✅ |
> | Architectural Compliance | 10/10 | ✅ |
> | Kotlin/Compose Idiomaticity | 9/10 | ✅ |
> | Test Coverage | 7/10 | ✅ |
> | Security & Data Safety | 10/10 | ✅ |
>
> ### Findings
>
> **Kotlin/Compose Idiomaticity**
> - Consider using a more explicit getter naming or documentation if `displayName` is intended
>   to be public API, as trim() is a simple transformation that might be better served as a
>   helper function or utility for edge cases.
>
> **Test Coverage**
> - No unit tests added for the `displayName` property. Recommend adding a simple test case
>   to verify trim() behavior with leading/trailing whitespace.
>
> This PR adds a computed `displayName` property to the Category model that returns a trimmed
> version of the name. The change is minimal, architecturally sound, and poses no security
> risks. Adding unit test coverage for the trim behavior would strengthen confidence in edge
> case handling.
>
> *Model: claude-haiku-4-5-20251001 · Retry: add label `ai-cr:review`*

---

URL komentarza na PR: https://github.com/Sroki21/cci-android/pull/6#issuecomment-0

---

## 6. Implementacja — kluczowe fragmenty

### `index.js` — wywołanie Anthropic API

```js
const response = await client.messages.create({
  model: 'claude-haiku-4-5-20251001',
  max_tokens: 1024,
  tools: [reviewTool],
  tool_choice: { type: 'tool', name: 'submit_review' },
  system: SYSTEM_PROMPT,            // 5 kryteriów arch/impl/idioms/tests/security
  messages: [{ role: 'user', content: userMessage }],
});
```

### Schemat narzędzia `submit_review`

```json
{
  "criteria": [
    { "id": "implementation_correctness",   "score": 1–10, "findings": [] },
    { "id": "architectural_compliance",     "score": 1–10, "findings": [] },
    { "id": "kotlin_compose_idiomaticity",  "score": 1–10, "findings": [] },
    { "id": "test_coverage",                "score": 1–10, "findings": [] },
    { "id": "security_and_data_safety",     "score": 1–10, "findings": [] }
  ],
  "verdict": "passed | failed",
  "summary": "2–4 zdania"
}
```

`verdict = failed` jeśli ANY score ≤ 5. Po review: label `ai-cr:passed` lub `ai-cr:failed` na PR.

---

## 7. Porównanie modeli — promptfoo eval

**Suite:** 3 testy × 3 modele = 9 przypadków · **9/9 pass (100%)** · ~41 s

| Test | Oczekiwany verdict | haiku-4.5 | sonnet-4.6 | opus-4.8 |
|------|--------------------|-----------|------------|----------|
| `clean` — computed property + test | `passed` | ✅ passed | ✅ passed | ✅ passed |
| `violation` — DAO w VM, GlobalScope, hardcoded key | `failed` | ✅ failed | ✅ failed | ✅ failed |
| `medium` — refactor SessionRepository | valid enum | ✅ passed | ✅ failed | ✅ failed |

### Scores (impl / arch / idiom / tests / sec)

| Model | clean | violation | medium | Verdict medium |
|-------|-------|-----------|--------|----------------|
| haiku-4.5 | 10/10/10/10/10 | 3/2/2/1/1 | 9/8/9/**7**/9 | passed |
| sonnet-4.6 | 9/10/10/8/10 | 2/2/1/1/1 | 7/6/8/**5**/8 | failed |
| opus-4.8 | 10/10/10/10/10 | 2/2/2/1/1 | 8/8/8/**5**/6 | failed |

### Koszt per 3 testy

| Model | Tokeny prompt | Tokeny output | Koszt (~USD) |
|-------|--------------|---------------|--------------|
| haiku-4.5 | 8 101 | 2 076 | **~$0.018** |
| sonnet-4.6 | 8 104 | 3 050 | ~$0.070 |
| opus-4.8 | 10 031 | 3 275 | ~$0.132 |

**Decyzja:** haiku wybrany do produkcji — 7× tańszy od opus, identyczna czułość na naruszenia arch/sec. Sonnet i opus dają `test_coverage: 5` dla refaktoru bez nowych testów — bardziej konserwatywne, potencjalnie generują false-negative'y dla małych zmian.

---

## 8. Pliki źródłowe

| Plik | Rola |
|------|------|
| `.github/workflows/pr-review.yml` | Definicja workflow (trigger, job, permissions) |
| `.github/actions/ai-review/action.yml` | Composite action — 4 kroki |
| `.github/actions/ai-review/index.js` | Logika: diff ingestion → Claude → komentarz + labele |
| `.github/actions/ai-review/package.json` | Zależność: `@anthropic-ai/sdk` |
| `.github/eval/promptfoo.yaml` | Suite eval: 3 modele × 3 testy |
| `.github/eval/fixtures/diff_clean.txt` | Fixture: czysta zmiana (oczekiwane: passed) |
| `.github/eval/fixtures/diff_violation.txt` | Fixture: naruszenia arch+sec (oczekiwane: failed) |
| `.github/eval/fixtures/diff_medium.txt` | Fixture: refactor z testami |

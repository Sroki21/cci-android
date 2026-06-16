# CI/CD AI Code Review — Plan Brief

> Full plan: `context/changes/ci-cd-code-review/plan.md`
> Requirements: `agent/requirements.md`

## What & Why

Add an AI-powered code review workflow that fires on every PR to `main`. Claude `claude-haiku-4-5-20251001` evaluates 5 stack-specific criteria (correctness, architecture, idiomaticity, test coverage, security). The goal is to catch regressions in MVVM boundaries and dual-write invariants before merge — without blocking hotfixes.

## Starting Point

A single CI workflow exists (`.github/workflows/android.yml`) running on push to `main`. It runs unit tests and ktlint only — no PR-triggered workflow exists. `agent/requirements.md` defines the full review spec: inputs, 5 criteria, structured output JSON schema, expected side-effects, and retry trigger.

## Desired End State

Every PR to `main` gets a formatted comment with per-criterion scores (1–10) and an overall `passed`/`failed` verdict. Two GitHub labels (`ai-cr:passed` / `ai-cr:failed`) make the verdict visible in PR lists. Adding `ai-cr:review` re-triggers the review on demand; the comment is updated in-place.

## Key Decisions Made

| Decision | Choice | Why | Source |
|---|---|---|---|
| Model | `claude-haiku-4-5-20251001` | ~10× cheaper than Sonnet; sufficient for rule-based Kotlin analysis | Plan |
| PR body | Yes, first 500 chars | Improves `implementation_correctness` scoring at minimal token cost | Plan |
| Diff scope | `.kt` and `.gradle` only | Eliminates asset/XML noise; all 5 criteria apply to Kotlin code | Plan |
| Runtime | Node.js + `@anthropic-ai/sdk` | Official SDK handles structured output parsing; native in GHA ecosystem | Plan |
| Large diff | Truncate to 80K chars + warning | No PR skipped; truncation note keeps the review honest | Plan |
| Merge blocking | No — labels only, exit 0 | Private repo; owner decides merge; no friction for hotfixes | Plan |

## Scope

**In scope:** PR workflow, composite action, Node.js review script, comment post/update, label management (passed/failed/retry), diff truncation warning

**Out of scope:** Merge blocking, ncc bundling, `.xml`/`.json`/`.md` file review, automated test harness for the script

## Architecture / Approach

```
PR opened / pushed / labeled (ai-cr:review)
        ↓
pr-review.yml  (job condition skips non-review labels)
        ↓
.github/actions/ai-review/  (composite)
  ├── bash: git diff origin/<base>...HEAD -- '*.kt' '*.gradle' → /tmp/review_diff.txt
  ├── setup-node@v4 + npm ci
  └── node index.js
        ├── read diff, truncate if > 80K chars
        ├── POST Anthropic API  →  tools + tool_choice: "submit_review"
        ├── parse tool_use.input  →  { criteria[], verdict, summary }
        ├── format markdown comment  (marker: <!-- ai-code-review -->)
        ├── GET/PATCH/POST PR comment
        └── DELETE old labels → POST verdict label → DELETE ai-cr:review
```

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. GHA Infrastructure | Valid workflow + action YAML + package.json | Incorrect input passing or missing `fetch-depth: 0` breaks diff silently |
| 2. Review script + GitHub feedback | Full `index.js`: API call, comment, labels | `ANTHROPIC_API_KEY` secret must be set before end-to-end verification |

**Prerequisites:** `ANTHROPIC_API_KEY` added as GitHub repository secret; `pull-requests: write` enabled in Actions settings

**Estimated effort:** ~1 session across 2 phases

## Open Risks & Assumptions

- Diff truncation at 80K chars may miss security-relevant changes in large refactors
- Haiku scores are non-deterministic — same diff may yield slightly different scores across re-runs
- `git diff origin/$BASE_REF...HEAD` requires `fetch-depth: 0` in checkout; silent failure if omitted

## Success Criteria (Summary)

- Test PR with `.kt` change → comment with 5-row score table + verdict label within ~30s
- `ai-cr:review` label → comment updated in-place, label replaced (not duplicated)
- PR with only `.md` changes → skip comment appears, no API call made

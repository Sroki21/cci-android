# CI/CD AI Code Review — Implementation Plan

## Overview

Introduce a GitHub Actions workflow that automatically reviews every PR to `main` using Claude `claude-haiku-4-5-20251001`. The review evaluates 5 stack-specific criteria, posts a formatted comment with per-criterion scores, and applies one of two labels (`ai-cr:passed` / `ai-cr:failed`). An on-demand retry is triggered by adding the `ai-cr:review` label.

## Current State Analysis

- `.github/workflows/android.yml` runs on `push` to `main` (unit tests + ktlint); no PR-triggered CI exists.
- No composite actions exist under `.github/actions/`.
- `agent/requirements.md` defines input parameters, 5 criteria with sub-checks, structured output JSON schema, expected side-effects, and the retry trigger.
- `context/changes/ci-cd-code-review/requirements.md` mirrors the criteria section.
- GitHub secret `ANTHROPIC_API_KEY` — **not yet set**; must be added before Phase 2 can be verified end-to-end.

### Key Discoveries

- `.github/workflows/android.yml:1` — existing CI job named `ci`, runs on `push` to `main`; new workflow must not conflict with it
- `agent/requirements.md` — contains the complete JSON Schema for structured output; copy verbatim into `index.js` tool definition
- Composite actions require `fetch-depth: 0` in the caller's checkout step to make `git diff origin/<base>...HEAD` work

## Desired End State

Every PR opened against `main` automatically receives:
- A PR comment with a verdict header, a 5-row score table, per-criterion findings (if any), and a one-paragraph summary.
- One of two labels: `ai-cr:passed` (green) or `ai-cr:failed` (red).
- On retry (via `ai-cr:review` label), the existing comment is updated in-place and labels replaced.

Verify by opening a test PR with at least one `.kt` change and confirming comment + label appear within ~30 seconds.

## What We're NOT Doing

- Not blocking merge on `ai-cr:failed` — labels only; workflow always exits 0
- Not reviewing `.xml`, `.json`, assets, or Markdown — only `.kt` and `.gradle`
- Not bundling Node.js dependencies with `ncc` — `npm ci` at action runtime is sufficient
- Not writing a test harness for the review script — manual end-to-end via a test PR is the verification path

## Implementation Approach

Two GHA files define the infrastructure (workflow + composite action). A single `index.js` handles the Anthropic API call, comment management, and label management. The composite action is self-contained under `.github/actions/ai-review/`.

---

## Phase 1: GHA Infrastructure

### Overview

Create the workflow file, composite action definition, and Node.js dependency manifest. After this phase the workflow is syntactically valid and `npm install` resolves cleanly — no logic yet.

### Changes Required

#### 1. Main PR workflow

**File**: `.github/workflows/pr-review.yml`

**Intent**: Trigger AI review on PR open, push, and on-demand retry via label. Delegate all logic to the composite action.

**Contract**:
- `on.pull_request`: `types: [opened, synchronize, labeled]`, `branches: [main]`
- Job-level `if`: `github.event.action != 'labeled' || github.event.label.name == 'ai-cr:review'` — skips all `labeled` events except the retry trigger
- Permissions: `pull-requests: write`, `contents: read`
- `actions/checkout@v4` with `fetch-depth: 0` (required for `git diff` against origin/base)
- Single step calling `./.github/actions/ai-review` with inputs: `github-token`, `anthropic-api-key`, `pr-number`, `pr-title`, `pr-body` (first 500 chars via `${{ github.event.pull_request.body && substring(github.event.pull_request.body, 0, 500) }}`), `base-ref`

#### 2. Composite action definition

**File**: `.github/actions/ai-review/action.yml`

**Intent**: Encapsulate the full review pipeline behind a composite action interface.

**Contract**:
- `using: composite`
- Inputs: `github-token`, `anthropic-api-key`, `pr-number`, `pr-title`, `pr-body`, `base-ref`
- Steps in order:
  1. `shell: bash` — collect filtered diff: `git -C "$GITHUB_WORKSPACE" diff origin/${{ inputs.base-ref }}...HEAD -- '*.kt' '*.gradle' > /tmp/review_diff.txt`
  2. `uses: actions/setup-node@v4` with `node-version: '20'`
  3. `shell: bash`, `working-directory: ${{ github.action_path }}` — `npm ci`
  4. `shell: bash`, `working-directory: ${{ github.action_path }}` — `node index.js` with all inputs forwarded as environment variables: `ANTHROPIC_API_KEY`, `GITHUB_TOKEN`, `PR_NUMBER`, `PR_TITLE`, `PR_BODY`, `BASE_REF`, `GITHUB_REPOSITORY`

#### 3. Node.js dependency manifest

**File**: `.github/actions/ai-review/package.json`

**Intent**: Declare `@anthropic-ai/sdk` as the sole runtime dependency. Node 20 native `fetch` handles all GitHub REST API calls.

**Contract**:
- `"type": "commonjs"` (CJS `require()` in index.js)
- `"dependencies": { "@anthropic-ai/sdk": "^0.30.0" }`
- `"engines": { "node": ">=20" }`

### Success Criteria

#### Automated Verification

- `npm install` in `.github/actions/ai-review/` exits 0
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/pr-review.yml'))"` exits 0
- `python3 -c "import yaml; yaml.safe_load(open('.github/actions/ai-review/action.yml'))"` exits 0

#### Manual Verification

- Workflow `AI Code Review` appears in the GitHub Actions UI under Actions tab
- No syntax errors shown in the workflow editor

**Implementation Note**: Pause after this phase and confirm that the workflow appears in GitHub UI before proceeding to Phase 2.

---

## Phase 2: Review script + GitHub feedback

### Overview

Implement `index.js` end-to-end: read diff → call Anthropic with structured output → format comment → post/update comment → manage labels.

### Changes Required

#### 1. Review script

**File**: `.github/actions/ai-review/index.js`

**Intent**: Orchestrate the full review cycle — diff ingestion, Anthropic API call, PR comment post/update, label management.

**Contract**:

_Diff ingestion:_
- Read `/tmp/review_diff.txt`; if `length > 80000` chars, truncate and set `truncated = true`
- If file is empty (no `.kt`/`.gradle` changes), post comment `"No Kotlin or Gradle files changed — skipping review."` and exit 0

_Anthropic API call:_
- `client.messages.create({ model: 'claude-haiku-4-5-20251001', max_tokens: 1024, tools: [reviewTool], tool_choice: { type: 'tool', name: 'submit_review' }, system: SYSTEM_PROMPT, messages: [...] })`
- `reviewTool.input_schema` — exact JSON Schema from `agent/requirements.md` (5 criteria enum, verdict, summary)
- `SYSTEM_PROMPT` — role as Android reviewer + the 5 criteria with all sub-checks embedded verbatim
- User message: `"PR: <title>\n\nDescription: <body>\n\nDiff:\n<diff>"`
- Extract result: `response.content.find(b => b.type === 'tool_use').input` — SDK returns a parsed JS object, no manual JSON.parse needed

_Comment formatting:_
- Open with hidden marker `<!-- ai-code-review -->` (used to find existing comment on retry)
- Verdict header: `## ✅ AI Review: PASSED` or `## ❌ AI Review: FAILED`
- Markdown table: 5 rows, columns `Criterion | Score | Status`; status cell: `✅` if score ≥ 6, `❌` if ≤ 5
- Per-criterion findings block (skipped for criteria with empty findings array)
- One-paragraph summary from `review.summary`
- Truncation warning line at bottom if `truncated = true`
- Footer: `*Model: claude-haiku-4-5-20251001 · Retry: add label \`ai-cr:review\`*`

_Comment post/update:_
- `GET /repos/{repo}/issues/{pr_number}/comments` — paginate if needed; find first comment whose `body` starts with `<!-- ai-code-review -->`
- Found → `PATCH /repos/{repo}/issues/comments/{comment_id}`; not found → `POST /repos/{repo}/issues/{pr_number}/comments`

_Label bootstrap (run once per workflow execution):_
- `POST /repos/{repo}/labels` for each of `ai-cr:passed` (color `0e8a16`), `ai-cr:failed` (color `d93f0b`), `ai-cr:review` (color `0075ca`); ignore HTTP 422 (label already exists)

_Label swap:_
- `DELETE /repos/{repo}/issues/{pr_number}/labels/ai-cr%3Apassed` — ignore 404
- `DELETE /repos/{repo}/issues/{pr_number}/labels/ai-cr%3Afailed` — ignore 404
- `POST /repos/{repo}/issues/{pr_number}/labels` with `{ labels: ['ai-cr:passed'] }` or `['ai-cr:failed']`
- `DELETE /repos/{repo}/issues/{pr_number}/labels/ai-cr%3Areview` — ignore 404 (removes retry trigger after use)

### Success Criteria

#### Automated Verification

- `node --check .github/actions/ai-review/index.js` exits 0

#### Manual Verification

- Open a test PR with at least one `.kt` change → comment with 5-row score table and a verdict label appears within ~30s
- Add `ai-cr:review` label to that PR → comment is updated in-place (not duplicated), label replaced
- Open a PR with only `.md` changes → comment `"No Kotlin or Gradle files changed"` appears, no label added

---

## Testing Strategy

### Manual Testing Steps

1. Add `ANTHROPIC_API_KEY` secret to the repo (`Settings → Secrets → Actions`)
2. Push a branch with at least one `.kt` change, open PR against `main`
3. Watch the `AI Code Review` workflow run; verify comment + label within ~30s
4. Add label `ai-cr:review` — verify comment updates, label replaced
5. Open a PR with only `.md` or asset changes — verify skip comment, no label

## Migration Notes

- `ANTHROPIC_API_KEY` must be added as a GitHub repository secret before Phase 2 end-to-end verification
- Confirm `Settings → Actions → General → Workflow permissions` allows `pull-requests: write`

## References

- Criteria + JSON schema: `agent/requirements.md`
- Existing CI: `.github/workflows/android.yml`

---

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands.

### Phase 1: GHA Infrastructure

#### Automated

- [x] 1.1 `npm install` in `.github/actions/ai-review/` exits 0
- [x] 1.2 `pr-review.yml` parses without YAML errors
- [x] 1.3 `action.yml` parses without YAML errors

#### Manual

- [x] 1.4 Workflow "AI Code Review" visible in GitHub Actions UI

### Phase 2: Review script + GitHub feedback

#### Automated

- [ ] 2.1 `node --check index.js` exits 0

#### Manual

- [ ] 2.2 Test PR with `.kt` change → comment + verdict label appears
- [ ] 2.3 `ai-cr:review` label → comment updated in-place, label replaced
- [ ] 2.4 PR with no `.kt`/`.gradle` changes → skip comment appears

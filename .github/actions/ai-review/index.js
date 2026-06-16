'use strict';

const fs = require('fs');
const Anthropic = require('@anthropic-ai/sdk');

const ANTHROPIC_API_KEY = process.env.ANTHROPIC_API_KEY;
const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const PR_NUMBER = process.env.PR_NUMBER;
const PR_TITLE = process.env.PR_TITLE;
const PR_BODY = process.env.PR_BODY || '';
const GITHUB_REPOSITORY = process.env.GITHUB_REPOSITORY;

const DIFF_MAX_CHARS = 80000;
const DIFF_PATH = '/tmp/review_diff.txt';
const REVIEW_MARKER = '<!-- ai-code-review -->';
const MODEL = 'claude-haiku-4-5-20251001';

const CRITERION_LABELS = {
  implementation_correctness: 'Implementation Correctness',
  architectural_compliance: 'Architectural Compliance',
  kotlin_compose_idiomaticity: 'Kotlin/Compose Idiomaticity',
  test_coverage: 'Test Coverage',
  security_and_data_safety: 'Security & Data Safety',
};

const SYSTEM_PROMPT = `You are an expert Android code reviewer specializing in Kotlin, Jetpack Compose, and clean architecture. Review the provided pull request diff against these 5 criteria:

1. **Implementation Correctness** (implementation_correctness)
   - Logic matches PR description; null safety and edge cases handled
   - Dual-write pattern respected: write to Room AND Firestore; always read from Room
   - Room migrations not skipped; autoMigrations or migration file present
   - suspend functions and coroutine scopes correct (no GlobalScope, cancellation handled)

2. **Architectural Compliance** (architectural_compliance)
   - Composable = render + event propagation only; no business logic
   - ViewModel = StateFlow/Flow; no direct Retrofit or Room DAO imports
   - Repository = data source coordination; no UI knowledge
   - New @HiltViewModel with @Inject constructor; new API services as @Singleton in NetworkModule
   - Models: domain models in model/, internal models in data/model/

3. **Kotlin/Compose Idiomaticity** (kotlin_compose_idiomaticity)
   - State hoisting respected; no state in Composable beyond remember {}
   - Flow collected via collectAsStateWithLifecycle() (not collectAsState())
   - No unnecessary !! or unsafe as? casts where Kotlin offers better tools
   - New Composables accept data as parameters — do not inject ViewModel internally

4. **Test Coverage Proportional to Risk** (test_coverage)
   - ViewModel and Repository: unit test with MockK + runTest
   - New PagingSource: test created via Repository (not ViewModel)
   - New critical paths (auth, Firestore sync): at minimum happy path + one error case
   - Composable JVM tests not required; instrumented tests optional

5. **Security and Data Safety** (security_and_data_safety)
   - No hardcoded credentials, API keys, or tokens in code or strings
   - No Context/Activity leak in ViewModel (only ApplicationContext via Hilt)
   - Destructive DB operations (DROP, DELETE *) require explicit justification comment
   - If PR changes Firestore document structure, verify alignment with Security Rules

Score each criterion 1–10 (1 = worst, 10 = best). Verdict: "passed" if ALL scores >= 6; "failed" if ANY score <= 5.
Provide actionable findings for each criterion (empty array if fully satisfied). Write a neutral 2–4 sentence summary.`;

const reviewTool = {
  name: 'submit_review',
  description: 'Submit the structured code review result',
  input_schema: {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    type: 'object',
    required: ['criteria', 'verdict', 'summary'],
    additionalProperties: false,
    properties: {
      criteria: {
        type: 'array',
        minItems: 5,
        maxItems: 5,
        items: {
          type: 'object',
          required: ['id', 'score', 'findings'],
          additionalProperties: false,
          properties: {
            id: {
              type: 'string',
              enum: [
                'implementation_correctness',
                'architectural_compliance',
                'kotlin_compose_idiomaticity',
                'test_coverage',
                'security_and_data_safety',
              ],
            },
            score: {
              type: 'integer',
              minimum: 1,
              maximum: 10,
              description: '1 = worst, 10 = best',
            },
            findings: {
              type: 'array',
              items: { type: 'string' },
              description: 'Actionable issues. Empty array if criterion is fully satisfied.',
            },
          },
        },
      },
      verdict: {
        type: 'string',
        enum: ['passed', 'failed'],
        description: 'passed if all scores >= 6; failed if any score <= 5',
      },
      summary: {
        type: 'string',
        description: '2-4 sentences for the PR comment body. Neutral tone.',
      },
    },
  },
};

async function githubRequest(path, method, body) {
  const url = `https://api.github.com${path}`;
  const opts = {
    method,
    headers: {
      Authorization: `Bearer ${GITHUB_TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'Content-Type': 'application/json',
      'User-Agent': 'ai-review-action',
    },
  };
  if (body !== undefined) {
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(url, opts);
  return res;
}

async function findExistingComment(repo, prNumber) {
  let page = 1;
  while (true) {
    const res = await githubRequest(
      `/repos/${repo}/issues/${prNumber}/comments?per_page=100&page=${page}`,
      'GET'
    );
    if (!res.ok) return null;
    const comments = await res.json();
    if (comments.length === 0) return null;
    const found = comments.find((c) => c.body && c.body.startsWith(REVIEW_MARKER));
    if (found) return found.id;
    if (comments.length < 100) return null;
    page++;
  }
}

async function postOrUpdateComment(repo, prNumber, body) {
  const existingId = await findExistingComment(repo, prNumber);
  if (existingId) {
    await githubRequest(`/repos/${repo}/issues/comments/${existingId}`, 'PATCH', { body });
  } else {
    await githubRequest(`/repos/${repo}/issues/${prNumber}/comments`, 'POST', { body });
  }
}

async function bootstrapLabels(repo) {
  const labels = [
    { name: 'ai-cr:passed', color: '0e8a16', description: 'AI review passed' },
    { name: 'ai-cr:failed', color: 'd93f0b', description: 'AI review failed' },
    { name: 'ai-cr:review', color: '0075ca', description: 'Trigger AI review retry' },
  ];
  for (const label of labels) {
    const res = await githubRequest(`/repos/${repo}/labels`, 'POST', label);
    // 422 = label already exists — ignore
    if (!res.ok && res.status !== 422) {
      console.warn(`Warning: could not create label ${label.name}: ${res.status}`);
    }
  }
}

async function swapLabels(repo, prNumber, verdict) {
  const toAdd = verdict === 'passed' ? 'ai-cr:passed' : 'ai-cr:failed';
  const toRemove = verdict === 'passed' ? 'ai-cr:failed' : 'ai-cr:passed';

  // Remove opposite verdict label
  await githubRequest(
    `/repos/${repo}/issues/${prNumber}/labels/${encodeURIComponent(toRemove)}`,
    'DELETE'
  );

  // Add verdict label
  await githubRequest(`/repos/${repo}/issues/${prNumber}/labels`, 'POST', { labels: [toAdd] });

  // Remove retry trigger label
  await githubRequest(
    `/repos/${repo}/issues/${prNumber}/labels/${encodeURIComponent('ai-cr:review')}`,
    'DELETE'
  );
}

function formatComment(review, truncated) {
  const passed = review.verdict === 'passed';
  const verdictHeader = passed
    ? '## ✅ AI Review: PASSED'
    : '## ❌ AI Review: FAILED';

  const rows = review.criteria
    .map((c) => {
      const label = CRITERION_LABELS[c.id] || c.id;
      const status = c.score >= 6 ? '✅' : '❌';
      return `| ${label} | ${c.score}/10 | ${status} |`;
    })
    .join('\n');

  const table = `| Criterion | Score | Status |\n|-----------|-------|--------|\n${rows}`;

  const findingsBlocks = review.criteria
    .filter((c) => c.findings && c.findings.length > 0)
    .map((c) => {
      const label = CRITERION_LABELS[c.id] || c.id;
      const items = c.findings.map((f) => `- ${f}`).join('\n');
      return `**${label}**\n${items}`;
    })
    .join('\n\n');

  const parts = [
    REVIEW_MARKER,
    verdictHeader,
    '',
    table,
  ];

  if (findingsBlocks) {
    parts.push('', '### Findings', '', findingsBlocks);
  }

  parts.push('', review.summary);

  if (truncated) {
    parts.push(
      '',
      '> ⚠️ Diff was truncated to 80 000 characters. Some files may not have been reviewed.'
    );
  }

  parts.push(
    '',
    `*Model: ${MODEL} · Retry: add label \`ai-cr:review\`*`
  );

  return parts.join('\n');
}

async function main() {
  // Validate required env vars
  if (!ANTHROPIC_API_KEY || !GITHUB_TOKEN || !PR_NUMBER || !GITHUB_REPOSITORY) {
    console.error('Missing required environment variables');
    process.exit(1);
  }

  // Diff ingestion
  let diffContent = '';
  try {
    diffContent = fs.readFileSync(DIFF_PATH, 'utf8');
  } catch (err) {
    console.error(`Could not read diff file: ${err.message}`);
    process.exit(1);
  }

  if (!diffContent.trim()) {
    await postOrUpdateComment(
      GITHUB_REPOSITORY,
      PR_NUMBER,
      `${REVIEW_MARKER}\nNo Kotlin or Gradle files changed — skipping review.`
    );
    console.log('No Kotlin/Gradle changes — skipped.');
    process.exit(0);
  }

  let truncated = false;
  if (diffContent.length > DIFF_MAX_CHARS) {
    diffContent = diffContent.slice(0, DIFF_MAX_CHARS);
    truncated = true;
    console.log(`Diff truncated to ${DIFF_MAX_CHARS} chars.`);
  }

  // Anthropic API call
  const client = new Anthropic({ apiKey: ANTHROPIC_API_KEY });

  const userMessage = `PR: ${PR_TITLE}\n\nDescription: ${PR_BODY}\n\nDiff:\n${diffContent}`;

  console.log('Calling Anthropic API...');
  const response = await client.messages.create({
    model: MODEL,
    max_tokens: 1024,
    tools: [reviewTool],
    tool_choice: { type: 'tool', name: 'submit_review' },
    system: SYSTEM_PROMPT,
    messages: [{ role: 'user', content: userMessage }],
  });

  const toolUseBlock = response.content.find((b) => b.type === 'tool_use');
  if (!toolUseBlock) {
    console.error('No tool_use block in response');
    process.exit(1);
  }
  const review = toolUseBlock.input;
  console.log(`Verdict: ${review.verdict}`);

  // Bootstrap labels and format comment
  await bootstrapLabels(GITHUB_REPOSITORY);
  const commentBody = formatComment(review, truncated);
  await postOrUpdateComment(GITHUB_REPOSITORY, PR_NUMBER, commentBody);
  await swapLabels(GITHUB_REPOSITORY, PR_NUMBER, review.verdict);

  console.log('Review posted successfully.');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

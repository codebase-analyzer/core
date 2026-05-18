# GitHub Actions integration for Code Owl

Three ways to run **Code Owl** in your GitHub workflows, from "drop it in, works today" to "one-line marketplace action."

| File | Use when... | Setup |
|---|---|---|
| **`basic.yml`** | You want the simplest possible setup. Scans on push/PR, uploads the HTML report as a workflow artifact. Builds the analyzer from source each run (~2 min). | Copy to `.github/workflows/codeowl.yml`. Default repo (`codebase-analyzer/core`) already wired. |
| **`pr-delta.yml`** | You want PRs to show *"this change adds 3 critical findings"* as a comment. Scans the base branch, scans the PR branch, computes deltas via the baseline mechanism, posts an auto-updating PR comment. | Copy to `.github/workflows/codeowl-pr.yml`. Requires `pull-requests: write` permission. |
| **`action.yml`** | You're publishing a reusable action to the GitHub Marketplace. One-line adoption: `uses: codebase-analyzer/action@v1`. | Put this `action.yml` at the **root** of a public repo (e.g. `codebase-analyzer/action`). Tag a release. Submit to Marketplace. |

---

## Quick start (basic.yml)

1. Copy `basic.yml` to `.github/workflows/codeowl.yml` in your project.
2. Commit + push. The next push or PR triggers a run.
3. After the run finishes: open the workflow run → **Artifacts** → download `codeowl-report` → open the HTML in a browser.

---

## PR delta workflow — what it actually does

The PR-delta workflow gives you the "did this PR make the codebase better or worse" answer in the PR conversation itself. The mechanism reuses the baseline + trend feature shipped in Code Owl's core:

```
┌─────────────────────────────────────────────────────────────┐
│  PR opened or pushed                                         │
│       │                                                       │
│       ▼                                                       │
│  Step 1: checkout PR branch, also checkout base SHA in       │
│          a git worktree                                       │
│       │                                                       │
│       ▼                                                       │
│  Step 2: scan BASE branch → write baseline.json              │
│          (captures total findings, severity counts,           │
│          tech debt €, migration score at that point)          │
│       │                                                       │
│       ▼                                                       │
│  Step 3: scan PR branch with --baseline=baseline.json        │
│          → emits summary.md with deltas                       │
│          → "Critical: ↑ 3 since baseline"                     │
│       │                                                       │
│       ▼                                                       │
│  Step 4: post the Markdown summary as a PR comment           │
│          (updates in-place on subsequent pushes)              │
└─────────────────────────────────────────────────────────────┘
```

The PR comment looks like this:

> ## 🟠 🦉 Code Owl report
>
> ### ❌ Trend: Regressed since baseline
>
> | Metric | Current | Δ vs baseline |
> |---|---:|---:|
> | Total findings | 1,452 | 🔴 ↑ 12 |
> | Critical | 8 | 🔴 ↑ 3 |
> | High | 24 | 🔴 ↑ 5 |
> | CVEs | 0 | — |
> | Tech-debt hours | 184 | 🔴 ↑ 14 |
> | Tech-debt cost | €18k | 🔴 ↑ €1k |
>
> ### Top priority fixes
>
> | # | Impact | Severity | Effort | Finding |
> |---:|---:|---|---|---|
> | 1 | **86/100** | CRITICAL | MEDIUM (~1h) | Bidirectional EAGER cycle detected |
> | 2 | **78/100** | CRITICAL | LOW (~0h) | @Transactional on private method |
> | ... | | | | |
>
> *Full HTML report available as a workflow artifact.*

---

## Composite action (action.yml)

The composite action is the **one-line adoption** path. Once published, any project can add this to a workflow:

```yaml
- uses: codebase-analyzer/action@v1
  with:
    project-path: .
    mode: lead
    baseline: .ca/baseline.json
    markdown-summary: .ca/summary.md
    fail-on-critical: 'true'
```

### Inputs

| Input | Default | Description |
|---|---|---|
| `project-path` | `.` | Path to the project root to scan. |
| `output-dir` | `analyzer-output` | Where to write the HTML report. |
| `mode` | `dev` | Initial HTML mode: `dev` / `lead` / `exec`. |
| `currency` | `EUR` | ISO-4217 currency code for tech-debt cost. |
| `rate-per-hour` | `100` | Hourly rate for tech-debt projection. |
| `baseline` | _(empty)_ | Path to baseline JSON for trend tracking. |
| `markdown-summary` | _(empty)_ | Path to write a Markdown summary for PR comments. |
| `fail-on-critical` | `false` | Exit non-zero when CRITICAL findings exist. |
| `analyzer-version` | `latest` | Pin a specific Code Owl release tag. |

### Outputs

| Output | Description |
|---|---|
| `report-path` | Path to the generated HTML report. |
| `summary-path` | Path to the generated Markdown summary (empty if not requested). |

### Publishing the action

1. Create a new public repo, e.g. `codebase-analyzer/action`.
2. Copy `action.yml` to the repo root.
3. Tag a release: `v1`, `v1.0.0`.
4. Submit to GitHub Marketplace (Settings → Actions → Marketplace).

---

## Tips

- **Maven cache is a 2× speed-up.** All templates include `actions/cache@v4` keyed on `pom.xml` hashes. Trust it.
- **`fetch-depth: 0` is intentional.** Code Owl reads git churn from the last 12 months to weight finding impact. Without full history it falls back to severity-only scoring.
- **`mode: lead` is a good default for CI.** Hides the dev-only suggestion code blocks (which can be long), keeps the priority + summary visible.
- **`fail-on-critical: true` is a strong signal but use carefully.** It will block PRs when ANY critical finding exists — including ones that already existed on `main`. Pair with `--baseline` (PR-delta workflow) for "only fail on NEW criticals."
- **Comment size.** The Markdown summary is capped to fit well under GitHub's 65 KB comment limit, even on huge codebases.

---

## Troubleshooting

**Workflow times out at "Build Code Owl"** — first run downloads the entire Maven dependency tree (~5 min). Subsequent runs hit the cache (~1 min). If timeouts persist, switch to the release-download approach.

**"Permission denied: pulls/...comments"** — add `permissions: pull-requests: write` to the workflow (already set in `pr-delta.yml`).

**Comment doesn't update on new push** — the comment is matched by the HTML marker `<!-- codeowl-pr-comment -->`. If you edited the comment manually and removed the marker, the next run creates a new comment.

**Git churn warning in logs (`no git history`)** — you forgot `fetch-depth: 0` on the checkout step. Without it, only the latest commit is available.

---

<sub>Code Owl is the product brand. The technical repo lives at `github.com/codebase-analyzer/core`.</sub>

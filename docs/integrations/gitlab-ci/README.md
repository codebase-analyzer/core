# GitLab CI integration for Code Owl

Two templates for running **Code Owl** in GitLab CI pipelines, from "drop it in, works today" to "MR comments with trend deltas."

| File | Use when... | Setup |
|---|---|---|
| **`basic.gitlab-ci.yml`** | You want scanned HTML uploaded as a job artifact on every push + every MR. Builds Code Owl from source in the runner (~2 min cold, ~30s with Maven cache hit). | Copy contents into your project's `.gitlab-ci.yml`. No tokens needed. |
| **`mr-delta.gitlab-ci.yml`** | You want MRs to show *"this change adds 3 critical findings"* as a comment that updates in place on every push. | Copy into `.gitlab-ci.yml`. Requires a project access token saved as `CODEOWL_TOKEN`. |

---

## Quick start (basic)

1. Open `basic.gitlab-ci.yml` and copy its contents.
2. Paste into `.gitlab-ci.yml` at the root of your project (or merge with your existing pipeline).
3. Commit + push. The next push or MR triggers the pipeline.
4. After the run finishes: open the pipeline → click the `codeowl-scan` job → **Browse** the artifacts → open `analysis-report.html` in a browser.

> 💡 If your project has **GitLab Pages** enabled, the report opens directly in the pipeline UI without needing to download anything. Otherwise it's a one-click download from the job's artifacts panel.

---

## MR-delta setup (the killer feature)

The MR-delta workflow gives you the *"did this MR make the codebase better or worse"* answer in the MR conversation itself. It reuses Code Owl's baseline + trend mechanism.

### One-time setup — create a project access token

1. Go to **your project → Settings → Access Tokens**
2. Click **Add new token**
3. Fill in:
   - **Token name:** `codeowl`
   - **Expiration date:** 12 months out (rotate when it expires)
   - **Role:** `Developer` (sufficient to post MR notes)
   - **Scopes:** ✅ `api`
4. Click **Create project access token**
5. **Copy the token immediately** — GitLab shows it only once

### One-time setup — save the token as a CI/CD variable

1. Go to **your project → Settings → CI/CD → Variables → Expand**
2. Click **Add variable**
3. Fill in:
   - **Type:** Variable
   - **Environment scope:** `*` (all environments)
   - **Visibility:** ✅ Masked
   - **Flags:** Uncheck "Protect variable" (so MRs from non-protected branches can read it). If your MRs come only from same-project protected branches, leave it Protected.
   - **Key:** `CODEOWL_TOKEN`
   - **Value:** *(paste the token from the previous step)*
4. **Add variable**

### Drop the workflow

1. Copy contents of `mr-delta.gitlab-ci.yml` into your `.gitlab-ci.yml`.
2. Commit + push.
3. Open a fresh MR. After ~3-5 min you should see a comment from your bot account starting with `## 🟠 🦉 Code Owl report`.
4. Push more commits to the MR — the comment updates in place (matched by a hidden `<!-- codeowl-mr-comment -->` HTML marker).

---

## What the MR comment looks like

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
>
> *Full HTML report available as a pipeline artifact.*

---

## Pinning to a specific Code Owl version

Both templates have a `CODEOWL_REF` variable at the top, defaulting to `main`. Once a stable release exists (e.g. `v0.1.0`), change it:

```yaml
variables:
  CODEOWL_REF: "v0.1.0"   # pin to a tag for reproducible CI runs
```

Pinning is strongly recommended for production pipelines — it prevents an upstream Code Owl change from breaking your pipeline overnight.

---

## GitLab-specific gotchas worth knowing

### 1. `GIT_DEPTH: 0` is required for trend impact scoring
The MR-delta template sets `GIT_DEPTH: 0` to fetch full history. Code Owl reads git churn (last 12 months) to weight finding impact. Without full history, all files look "cold" and the impact ranking degrades to severity-only. GitLab CI shallow-clones by default — opt out explicitly.

### 2. Token visibility on fork MRs
By default GitLab CI variables marked *Protected* are NOT exposed to MRs coming from unprotected branches (typical for fork-based contributions). The MR-delta workflow needs the token to post comments, so either:
- Leave `CODEOWL_TOKEN` Unprotected (any branch can post) — fine for internal repos
- Mark it Protected — but then MRs from non-protected branches won't get comments

For an open-source project taking external contributions: use Unprotected + a token with `Reporter` role (write to MR notes is allowed at Reporter, not just Developer — saves accidental damage if leaked).

### 3. The `maven:3.9-eclipse-temurin-17` image is the safe default
- ~250MB pull, ~5s on warm runner cache
- Has Maven 3.9 + JDK 17 + git + curl pre-installed
- Avoid `maven:slim` — missing apt, can't install jq

### 4. `before_script` runs in every job using this template
If you're including this template alongside other jobs that have their own `before_script`, GitLab CI **overrides** rather than merges. Use `extends:` or `!reference` to compose them.

### 5. Maven cache key
We key the Maven cache on `${CI_COMMIT_REF_SLUG}-mvn`, so each branch has its own cache. If you'd rather share across branches (faster but cache pollution risk), change to a static key like `mvn-deps`.

---

## Fail the pipeline on critical findings

Code Owl exits with code `2` when at least one CRITICAL finding exists. To make the pipeline fail on that:

```yaml
script:
  - java -jar /tmp/analyzer.jar "$CI_PROJECT_DIR" --output codeowl-output --mode lead
allow_failure: false   # default — exit code 2 fails the job
```

**Pair this with `--baseline`** (use the MR-delta template) so the pipeline only fails on **NEW** criticals introduced by the MR — not ones that already existed on the target branch. Otherwise every MR on a debt-heavy codebase fails immediately.

---

## Troubleshooting

**Pipeline times out at "Build Code Owl"** — first cold run downloads the entire Maven dependency tree (~3-5 min). The `cache:` directive should cache this between runs. If timeouts persist, increase the job's `timeout:` and/or switch to a release-download approach (planned, not yet shipped).

**Comment never appears on the MR** — check the job log near "Creating new MR note":
- `401 Unauthorized` → `CODEOWL_TOKEN` is missing, expired, or wrong scope
- `403 Forbidden` → token role too low (`Guest` instead of `Developer` / `Reporter`)
- `404 Not Found` → wrong `$CI_MERGE_REQUEST_PROJECT_ID` (rare; only happens on cross-project MRs)
- `Successfully posted` but no comment in UI → posted to a different MR; check `$CI_MERGE_REQUEST_IID`

**Comment doubles on each push** — the find-and-update logic matches by the `<!-- codeowl-mr-comment -->` HTML marker. If you edited the comment manually and removed the marker, the next run creates a new comment. Don't edit; reply with a sibling note instead.

**`apt-get install jq` fails** — your runner image isn't Ubuntu/Debian-based. Either switch to `maven:3.9-eclipse-temurin-17` or pre-install `jq` in your runner.

**"git log: bad revision" when fetching base branch** — your runner shallow-cloned. Set `GIT_DEPTH: 0` at the job or pipeline level.

---

<sub>Code Owl is the product brand. Technical project lives at `github.com/codebase-analyzer/core`.</sub>

# Code Owl — CI integrations

| Platform | Status | Templates |
|---|---|---|
| **GitHub Actions** | ✅ Available | [github-actions/](github-actions/) — basic.yml, pr-delta.yml, composite action.yml |
| **GitLab CI** | ✅ Available | [gitlab-ci/](gitlab-ci/) — basic.gitlab-ci.yml, mr-delta.gitlab-ci.yml |
| **Jenkins** | ⏳ Planned | Pipeline DSL template — coming when there's customer demand |
| **Bitbucket Pipelines** | ⏳ Planned | Same |
| **Azure DevOps** | ⏳ Planned | Same |
| **CircleCI** | ⏳ Planned | Same |

## Which one do I need?

- **Use GitHub Actions** if your project is hosted on `github.com`. Three flavors: simple HTML artifact upload, PR comments with trend deltas, and a Marketplace-ready composite action.
- **Use GitLab CI** if your project is hosted on `gitlab.com` or self-hosted GitLab. Two flavors: artifact upload + MR comments with trend deltas.
- **Use another platform?** Code Owl is a single Java JAR with a CLI. Any CI system that can run `java -jar` and post HTTP requests can integrate it — the existing templates are reference implementations. Open an issue if you'd like us to ship a template for your platform.

## Pattern shared across all integrations

Every integration follows the same two-phase pattern:

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 1 — Basic scan (low value, low effort to adopt)       │
│  Run Code Owl on push/PR, upload HTML report as artifact     │
│       │                                                       │
│       ▼                                                       │
│  Phase 2 — PR/MR delta (high value, slightly more setup)     │
│  Scan base branch + PR branch with --baseline, post delta    │
│  comment in PR/MR conversation. "+3 critical, +€1k debt"     │
└─────────────────────────────────────────────────────────────┘
```

Phase 2 is what makes Code Owl feel "alive" in the developer workflow — every PR gets immediate, contextual feedback on whether it makes the codebase better or worse.

## Building your own integration

The CLI is the integration surface. Three flags are doing the heavy lifting:

- `--write-baseline <file>` — run once on the target branch, captures full state
- `--baseline <file>` — run on the working branch, suppresses old findings + enables trend deltas
- `--markdown-summary <file>` — emits a compact (~2 KB) Markdown report suitable for PR/MR comments

See the [main README](../../README.md#cli-reference) for the full CLI surface.

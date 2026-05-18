# Codebase Analyzer — Improvement Plan & Strategic Roadmap

> Status: **Phase A complete · Phase B largely complete · Strategy committed 2026-05-17**
> Last updated: 2026-05-17

---

## Part 0 — Commercial Strategy (committed 2026-05-17)

### Product positioning
Specialist tool for **Java/Spring/Hibernate legacy modernization**. Not a generic
static analyzer competing with Sonar — we win on depth in this niche where Sonar
is shallow (Hibernate fetch strategies, transaction propagation, migration blockers).

### Distribution + monetization model: **OSS CLI + Paid SaaS**

| Tier | Audience | Pricing intuition (not validated) | What's in it |
|---|---|---|---|
| **Free (OSS)** | Solo devs, evaluators, OSS projects | $0 | CLI scan, single-project HTML report, all analyzers run, top-5 critical findings shown, baseline read/write, dev/lead/exec modes, manual baseline comparison |
| **Team** | Teams / engineering leads | $50-150 / project / month | Everything Free + SaaS dashboard, **trend tracking with history**, GitHub Action + PR comments, Slack/Teams alerts, live CVE DB updates, baseline storage in cloud, multi-environment runs |
| **Enterprise** | Fortune 500 / regulated industries | from $25k / year | Everything Team + multi-project rollup, **runtime JFR instrumentation**, custom rules, SAML SSO, air-gapped on-prem deploy, custom reports, SLA + dedicated CSM, compliance / vendor-questionnaire exports |

**Why not pay-as-you-go:** scans are infrequent and bursty; PAYG creates billing
friction at the wrong moment. Recurring per-project subscriptions match how teams
actually consume the tool (continuous monitoring of N projects).

**Why not "freemium" of the CLI:** the OSS CLI is the marketing funnel. Crippling
it would kill adoption. The SaaS is the upsell, not the CLI itself.

### What we explicitly say NO to
- Building SaaS infrastructure before validating buyer interest with 2-3 paid pilots
- Competing with SonarQube on breadth (they win on language count, we lose)
- Heavy investment in PMD wrapping / generic dead code / commodity analyses
- IDE plugins before we have paying customers
- Per-finding-fixed pricing (auto-fix is wrong axis to monetize)
- Building anything without a marketing story attached to it

### 6-month execution plan
| Month | Focus | Output | Tier impact |
|---|---|---|---|
| 1 | Trend tracking + GitHub Action + **GitLab CI template** + CVE DB auto-update | Repeatable scans in CI on both platforms | Free + Team hooks |
| 2 | AI-explained findings (opt-in LLM endpoint, BYO API key) + Slack/Teams webhook | Tool feels "alive" in workflow | Team feature |
| 3 | Public scan reports of 5 famous Java OSS projects + 5 deeply technical blog posts | Distribution / SEO / stars | Marketing |
| 4 | Runtime JFR agent (Hibernate stats first — real N+1 detection) | The actual moat | Enterprise feature |
| 5 | First paid pilot with friendly company (free, in exchange for case study) | Validation, logo, real feedback | Validation |
| 6 | Decide: build SaaS infrastructure OR continue OSS+pilot model | Go/no-go on SaaS | Strategy |

### Marketing strategy (parallel to features)
1. **Each finding type → SEO blog post.** Educational content we already wrote is gold; repurpose it. e.g. "Why your @Transactional(readOnly=true) is silently ignored."
2. **Public scans of famous OSS Java projects** (JHipster, Spring PetClinic, Apache projects). Viral "we found N issues in $POPULAR" posts.
3. **GitHub Action template + GitLab CI template.** Viral devs-bringing-the-tool-into-their-org distribution.
4. **Speaker circuit:** SpringOne, Devoxx, JavaOne.
5. **Comparison content:** head-to-head with Sonar/Snyk on specific code examples in our niche.
6. **Time with the migration wave:** Spring Boot 2.x EOL (Nov 2025), Hibernate 5 EOL, Java 8 enterprise extended support ending. ~18-24 month window of maximum urgency.

### Honest risks acknowledged
- Single founder/small team selling enterprise = brutal sales cycles
- SonarSource can copy any feature we ship within a quarter
- SaaS infra is non-trivial work we haven't started
- The Java/Spring market is mature; disruption requires being very sharp in one niche

### Strategic additions (2026-05-18)

**Product brand:** 🦉 **Code Owl** (tagline: "Analyze. Understand. Elevate."). Technical
identifiers remain `codebase-analyzer` for stability — see README footer for the why.

**GitLab CI integration — added as Month 1 must-have.** Phoenix (our primary
near-customer) uses GitLab CI, not GitHub Actions. We need parity templates:
- `docs/integrations/gitlab-ci/basic.gitlab-ci.yml` — scan on push/MR, upload HTML artifact
- `docs/integrations/gitlab-ci/mr-delta.gitlab-ci.yml` — base-vs-MR delta, comment via GitLab REST API
- `docs/integrations/gitlab-ci/README.md` — setup guide
Roughly 1-2 hours work. Mirrors the GitHub Actions templates conceptually.

**AI features — Team-tier amplifier, NOT Free-tier core.** Decided 2026-05-18 after
considering Codacy's AI-Assisted Engineering positioning. The OSS CLI stays 100%
offline (offline-pure is a differentiator we don't surrender — critical for
finance/healthcare/gov segments). AI lives in the Team tier as opt-in features
amplifying static analysis, not replacing it:
- **"Explain in my codebase" LLM endpoint** — takes a finding + surrounding code,
  returns a personalized explanation matching the user's naming conventions and patterns
- **Migration fix generator** — LLM produces an OpenRewrite recipe or git diff for
  migration blockers, using the user's code style as reference
- **PR coaching agent** — reads PR diff + Code Owl findings, writes a context-aware
  GitLab/GitHub comment grounded in real static analysis (not hallucination)
- **BYO-API-key flag** for self-hosted teams that want AI without our SaaS

What we explicitly will NOT do with AI:
- AI as the headline product feature (crowded; our moat is depth, not AI)
- AI for finding *detection* (LLMs are worse than AST for known patterns; trust killer)
- Forced AI / no offline mode (lose enterprise-regulated segments)

Position: **"Static analysis you can trust, with AI to help you act on it"** — not
"AI-powered everything." Codacy positions on general code-review AI; we go deeper
on specific Java/Spring/Hibernate migration + debt workflows that benefit from AI
*because* the static signal is high-quality.

---

## Part 1 — Diagnosis: Why It's Noisy Today

The current solution has three structural problems, not a list of small bugs:

1. **No confidence model.** Every finding is treated equally. A grep-matched dead bean and a CVE both end up as "findings." There's no way to suppress, baseline, or rank by trust.
2. **Heuristic thresholds masquerading as rules.** Hibernate field counts, cascade thresholds, "3+ setters = OK" — these are guesses. They will misfire on every codebase that doesn't match the implicit shape.
3. **Wrapping PMD as-is.** PMD ships ~200 rules designed for greenfield Java. Most are stylistic noise on legacy enterprise code. Excluding 3 rules and patching 2 doesn't change that — we've inherited PMD's philosophy, which is the opposite of "signal over noise."

The current architecture treats the codebase like a static text file. To be a marketable product, it needs to treat the codebase like a **system with history, context, and risk**.

---

## Part 2 — Phase A: Kill the Noise (Foundation)

Without these, no new feature will land well. They're prerequisites, not features.

### A1. Confidence + Provenance on Every Finding
Add to `Finding`:
- `Confidence`: `CERTAIN | HIGH | MEDIUM | LOW` (e.g., CVE = CERTAIN, dead bean = LOW)
- `evidence`: list of concrete proof points (line refs, regex matches, AST nodes hit)
- `ruleId`: stable string for suppression (e.g., `hibernate.eager-cycle.bidirectional`)

The HTML report sorts and filters on confidence. Default view hides LOW.

### A2. Suppression & Baseline System
- `.codebase-analyzer.yml` config file: ignore rules globally, per-file, per-package
- `// @analyzer-ignore: rule-id reason` inline comment support
- **Baseline mode**: `--baseline baseline.json` — only show findings *new* since baseline. This single feature is what makes static analysis adoptable on legacy code. Sonar charges enterprise money for this.

### A3. Replace DeadBeanAnalyzer with a Real Reference Graph
Stop grepping. Build a symbol-resolved reference graph using JavaParser's `JavaSymbolSolver`:
- Resolve every type/method/field reference
- A bean is dead only if no resolved reference exists AND no Spring config XML/annotation references it AND no SPI/META-INF declaration includes it
- Drops false positives by ~80%+ on real codebases

### A4. Trim PMD to a Curated Set
Don't run all of `bestpractices`/`errorprone`/`performance`. Whitelist ~25 rules that produce real bugs (e.g., `EmptyCatchBlock`, `UnusedPrivateField`, `CompareObjectsWithEquals`, `MissingOverride`). Drop the stylistic noise (`MethodNamingConventions`, `LongVariable`, `ShortClassName`, `LocalVariableCouldBeFinal`, etc.).

### A5. Resolve Spring Boot BOM Versions
Currently the security scanner finds zero CVEs on most real projects because BOM-managed versions aren't resolved. Walk `<dependencyManagement>` with `<scope>import</scope>`, fetch the imported BOM (locally cached `~/.m2`), recursively resolve its `dependencyManagement`. **This unlocks the entire security feature.**

---

## Part 3 — Phase B: Cutting-Edge Differentiating Features

These are what make it marketable. Each one solves a problem no free tool addresses well for legacy Java shops.

### B1. Migration Copilot (Java 8→21, Spring Boot 2→3, Hibernate 5→6)
Not just a checklist — an **interactive migration plan**:
- Per-file blocker map with effort estimate (S/M/L) and dependency order (fix X before Y)
- "Migration readiness score" 0–100 per area
- **Generated OpenRewrite recipe YAML** so users can apply mechanical fixes via a tool they already trust
- Estimated total person-days based on blocker count × complexity weights

**Why it sells:** Every Java shop is staring down the JDK 8/Spring Boot 2 EOL cliff. Consultants charge $50k+ for this assessment.

### B2. Runtime-Aware SQL & Transaction Analysis
Static SQL inspection beyond N+1:
- Parse JPQL, HQL, `@Query`, native queries → flag missing indexes, cartesian joins, `SELECT *` over wide entities, pagination on unsorted queries
- Build a **transaction propagation graph**: trace `@Transactional` calls across services, flag long transactions (5+ repository calls), flag REST endpoints with no transaction at all
- Cross-reference with entity graph: "this service mutates Entity X but isn't transactional"

**Why it sells:** Production-incident pattern detection. Sonar/PMD don't do cross-method transactional analysis.

### B3. Architectural Layering & Modularity Report ✅ DONE (2026-05-16)
- Build the package dependency graph
- Detect cycles, god packages (high fan-in + fan-out), layering violations (controller→repository skipping service)
- **Modularization suggestions**: "These 14 classes could become module `payments-core` with zero outgoing deps to `legacy-utils`" — sets up customers for Spring Modulith or actual module extraction
- Visualize as collapsible sunburst in HTML

**Why it sells:** Boards ask "can we extract a microservice?" — this answers it with evidence.

**Shipped (MVP scope, ~900 LOC):**
- `ArchitectureAnalyzer` registered in the pipeline; emits 3 finding categories:
  `ARCHITECTURE_CYCLE`, `ARCHITECTURE_LAYERING`, `ARCHITECTURE_GOD_CLASS`.
- `PackageGraph` + iterative Tarjan's SCC for cycle detection (handles huge graphs without recursion limits).
- `LayerInference` heuristic maps package names to canonical layers (controller/service/repository/entity/dto/config/util)
  with ~20 token aliases; flags inversions (Controller→Repository, Service→Controller, Repository→Service,
  Entity→{Service,Repository,Controller}).
- `ClassMetric` tracks methods + LOC + outgoing deps + fields + Spring stereotype; god class triggered when any
  threshold is breached (40 methods, 800 LOC, 25 fan-out), ranked by composite weight (Spring stereotype amplifies x1.3).
- Dedicated **Architecture** page in the HTML report sidebar — 5 KPI strip, cycle blocks with closing-import evidence,
  layering violations table with layer badges, god classes table with hot/warm thresholds, top fan-out packages.
- `EffortTable` extended with architecture estimates (cycle: 240min, layering: 60min, god class: 480min).
- `ImpactScorer` gives architecture findings category bonuses (cycle +10, god class +5).
- Phoenix smoke test: 675 packages, 17 cycles, 155 layering violations, 25 god classes, 197 new findings,
  tech debt rose €278k → €321k (+€43k for structural debt).

**Deferred (not in v1, will add if customers ask):**
- Coupling/cohesion metrics (Robert Martin's I, A, D) — academic, low actionability.
- Maven module-level graph (substantial overlap with packages).
- DOT/Graphviz/sunburst visualization — table is readable; graph rendering bloats HTML.
- Modularization suggestion engine ("extract these N classes as module X").
- Custom architecture rule DSL (`.codebase-arch.yml`).

### B4. Risk-Weighted Prioritization (Git-Aware)
Replace flat severity with a **business risk score** = `severity × file_churn × ownership_breadth × blast_radius`:
- `git log` for churn (last 12 months)
- `git shortlog -sn` for owner count (high = orphan code, risky)
- Blast radius from the reference graph (how many things depend on this class)
- Output: **"Top 10 things to fix this sprint"** ranked by ROI

**Why it sells:** Every analyzer dumps 5,000 findings. Nobody fixes them. The top-10 list is what an engineering manager actually uses.

### B5. Tech Debt Quantification ($ + time)
Map each finding category to:
- Estimated remediation hours (from a built-in calibrated table)
- Hourly rate (configurable, default $100/hr)
- Output a **debt dashboard**: "Current debt: 1,240 hours / $124k. Debt added last 90 days: +180h."
- Per-team breakdown if `CODEOWNERS` exists

**Why it sells:** Engineering leaders need to justify refactor budgets to CFOs. This generates the slide.

### B6. Auto-Fix Mode with Safe Patches
Generate unified diffs for safe transformations (separate PR per category):
- `new FileInputStream` → `Files.newInputStream`
- Empty catch → logged catch
- `==` on String → `.equals()`
- Add missing `@Override`
- `@Transactional private` → remove `private`
- `--fix --confidence safe` applies, `--fix-preview` shows the diffs

**Why it sells:** Concrete time savings. Sonar shows the issue; this fixes it.

### B7. Report Modes for Different Audiences ✅ DONE (2026-05-17)
Same data, three output flavors:
- **`--report dev`** (current): file-level, technical, full findings
- **`--report lead`**: team-level, top-10, debt trends, sprint-ready ticket export (Jira CSV)
- **`--report exec`**: one-page PDF, debt $, migration readiness, risk heatmap

**Why it sells:** A tool the manager *and* the developer both want installed is a tool that survives procurement.

**Shipped (~600 LOC):**
- `--mode <dev|lead|exec>` CLI flag sets the initial mode rendered into `<body data-mode="...">`.
- Sidebar dropdown lets viewers switch modes on the fly without re-running the scan.
- CSS rules `body[data-mode="X"] [data-modes]:not([data-modes~="X"]) { display: none }`
  drive visibility; elements with no `data-modes` attribute are always visible (default).
- Tagged content:
  - **Findings page** + **Architecture page**: hidden in exec mode
  - **Dependencies** + **Project Stats** pages: dev-only
  - **Top critical issues teaser** + **codebase composition card**: hidden in exec
  - **Finding suggestion code blocks** + **per-file location tables**: dev-only (lead sees aggregated card without code)
- New **Executive Summary panel** at top of Overview, visible only in exec mode:
  - Health verdict: Healthy / Manageable / Concerning / Critical (computed from critical count, CVE count, tech debt hours)
  - 1-paragraph narrative pulling KPIs (findings, CVEs, debt €, migration score)
  - Top 3 risks computed from CVE count, cycles, migration blockers, TX issues, fetch issues, god classes
  - Quick-win plan ROI: "fix top N items in ~Xh, costs €Y" using the prioritized finding list
- **Print stylesheet** (`@media print`): drops sidebar/filters/pagination, switches to light theme, prints the active mode.
  Browsers handle PDF export via Print → Save as PDF.
- Phoenix verdict: Critical (5039 critical findings, €317k debt). Default mode dev unchanged.

**Deferred:**
- Jira CSV export (mentioned in original B7) — separate command surface, low MVP value vs effort.
- Per-team CODEOWNERS slicing — separate feature (proposed as "next" after B7).

---

## Part 4 — Sequencing

| Phase | Items | Rationale |
|---|---|---|
| **A (Foundation)** | A1 → A4 → A3 → A2 → A5 | Confidence model first; PMD trim is fast wins; reference graph unlocks dead-code accuracy and B3/B4; baselines need confidence scoring; BOM resolution unlocks B1/security |
| **B1** | Migration Copilot | Highest commercial pull, isolated from runtime concerns, reuses A's reference graph |
| **B4 + B5** | Prioritization + Debt $ | Both build on git integration; ship as one "Manager Dashboard" release |
| **B2** | Runtime-aware SQL/TX | Deep technical moat; reuses entity graph + reference graph |
| **B3** | Architectural report | Visual feature; good marketing screenshots |
| **B6** | Auto-fix | Highest complexity; needs A's confidence model to be safe |
| **B7** | Audience modes | Wraps everything for go-to-market |

---

## Part 5 — Positioning Statement

> **"SonarQube tells you what's wrong. Codebase Analyzer tells you what to fix first, what it'll cost, and how to migrate off your dying Java 8 / Spring Boot 2 stack."**

Three pillars: **signal over noise** (Phase A), **migration intelligence** (B1), **business context** (B4/B5/B7).

---

## Progress Log

- **2026-05-15**: Plan exported. Starting Phase A.
- **2026-05-15: A1 (Confidence + Provenance) — DONE**
  - Added `Confidence` enum (`CERTAIN | HIGH | MEDIUM | LOW`) to `Finding`
  - Added `ruleId` field (stable, suppression-friendly)
  - Added `evidence` list field (concrete proof points per finding)
  - Backward-compatible constructor preserved; new full constructor available
- **2026-05-15: A4 (PMD trim) — DONE**
  - Replaced `<rule ref="category/...xml">` wholesale imports with curated whitelist
  - ~50 high-signal rules retained (true bugs, NPE patterns, real performance issues)
  - ~25+ stylistic / opinion rules dropped (`InsufficientStringBufferDeclaration`, `LooseCoupling`, `MethodReturnsInternalArray`, etc.)
  - `PmdAnalyzer` now sets per-rule confidence (deterministic AST → HIGH; heuristics → MEDIUM)
- **2026-05-15: HTML wiring — DONE**
  - Confidence badge added to every finding card (color-coded: green/purple/yellow/grey)
  - Confidence filter group added: All / Trusted (default) / Certain / High / Medium / Low
  - "Trusted" view (default) hides LOW-confidence findings to reduce noise
  - `DeadBeanAnalyzer` findings marked LOW confidence pending A3 rewrite
- **Smoke test on RBAC project**: 66 findings total (down from much higher), 0 critical, 45 PMD (curated), 11 high. Default report view displays trusted findings only.

### Phase A completion

- **2026-05-15: A3 (AST reference graph for dead beans) — DONE**
  - Replaced grep-based reference search with a single-pass AST index over every Java source
  - Indexes `ClassOrInterfaceType`, `NameExpr`, `MethodReferenceExpr`, and bean-name `StringLiteralExpr` references
  - Falls back to non-Java config grep (XML/YAML/properties/Spring SPI `.imports`) only when no AST reference exists
  - Bean-name string literals (e.g. `getBean("fooService")`) and FQN-style strings (`Class.forName("a.b.Foo")`) detected
  - Findings now `Confidence.HIGH` (deterministic AST + config scan) — no longer hidden by default

- **2026-05-15: A2 (Suppression + baseline) — DONE**
  - `.codebase-analyzer.yml` config: `ignore-rules:` and `ignore-paths:` (glob)
  - Minimal YAML subset parser, no new dependencies
  - Inline `// @analyzer-ignore: ruleId reason` comment support — checks the finding line and the line above it; `*` wildcards all rules
  - `Baseline` JSON model with stable line-number-free fingerprints
  - CLI: `--baseline path.json` and `--write-baseline path.json`
  - Three suppression sources compose via OR; engine logs how many were filtered by each

- **2026-05-15: A5 (Spring Boot BOM resolution) — DONE**
  - `ProjectMetadataDetector` now resolves `<scope>import</scope>` BOMs from `~/.m2/repository`
  - Recursive BOM walk (BOMs that import other BOMs — Spring Boot imports Spring Framework BOM, etc.)
  - Merges BOM properties + managed versions into the project's resolution map

### Validation on real legacy codebases

**RBAC** (small Spring Boot service, 4 beans, ~50 source files): 66 findings, 0 critical. Default "Trusted" filter shows clean results.

**Phoenix** (9,199 Java files, 807 JPA entities, 348 beans, multi-module legacy intranet):
- Parse rate: **9199/9199 successful** (0 errors)
- Findings: 4,880 raw → grouped into 60 unique cards in the HTML report (all HIGH/MEDIUM confidence)
- **7 critical findings**: 4 bidirectional EAGER cycles + multiple `@Transactional` on private methods — **real production bugs**
- 419 high findings
- Dead bean analyzer: **only 3 unused beans found across 348 candidates** (huge precision win over the old grep approach)
- AST reference index: 35,250 distinct identifiers, 25,202 bean-name string literals
- Performance: 80s end-to-end (PMD takes 46s; analyzers themselves are sub-second)

### Phase A — Complete

All five foundation items shipped. The tool now:
1. Carries confidence + provenance on every finding
2. Hides speculative findings by default in the report
3. Supports config-file, inline-comment, and baseline suppression
4. Uses real AST reference graphs (not text grep) for dead-code detection
5. Resolves Spring Boot BOM-managed dependency versions

### Phase A.5 — Report UX simplification

- HTML card layout simplified: per-card overview with ONE labeled example (description + suggestion), then a plain "Impacted classes" table. No per-occurrence expand-to-detail.
- Rationale: 4,880-occurrence cards with per-row hidden detail panels made the page freeze. Trading off: one concrete labeled example beats a confusing mix of "first finding's description" + "richest suggestion from a different finding".
- Confidence filter relabeled: "Trusted" → "≥ Medium" (clearer that it's a meta-range, not a level)

### Phase B1 — Migration Copilot — DONE (2026-05-15)

**Shipped:**
- `MigrationRule`, `MigrationRuleType`, `MigrationScore` models
- `MigrationRuleRegistry` — ~30 curated rules covering:
  - Java 8 → 17/21: javax.xml.bind, javax.activation, javax.annotation, javax.transaction, CORBA, sun.misc.Unsafe, Nashorn, RMI Activation, Applet, JavaFX unbundling, java.security.acl
  - Spring Boot 2 → 3: full Jakarta namespace migration (persistence/servlet/validation/ws/mail), WebSecurityConfigurerAdapter, PathMatcher→PathPattern, spring.factories deprecation, Spring Cloud version alignment, several renamed config properties
  - Hibernate 5 → 6: criterion API removal, org.hibernate.Query deprecation, @Type(type="...") form removal, generator changes, removed config flags, versioned dialect class deprecation
- `MigrationReadinessAnalyzer` — AST scan for imports / class refs / annotations + config-file scan for properties/spring.factories. Auto-detects active migration areas from `ProjectMetadata` (Java version + dependency coordinates).
- `MigrationScoreCalculator` — per-area scoring `100 − (CRIT×15 + HIGH×8 + MED×3 + LOW×1)`, clamped. Overall = min across areas. Effort estimate summed from per-rule minute weights.
- `AnalysisResult.migrationScore` field; `AnalysisEngine` populates it from the unfiltered raw findings (so suppression doesn't inflate the score).
- HTML report section: overall score gauge + verdict badge (Ready / Mostly Ready / Significant Work / Major Rewrite), per-area progress bars with blocker pills and effort estimate, links into the MIGRATION_BLOCKER category for specific locations.
- CLI registration.

**Phoenix validation (Java 1.8 codebase):**
- Active areas auto-detected: **Java** (project's pom resolves Spring/Hibernate versions via BOMs we don't unwind yet, so those areas didn't trigger)
- **368 migration blockers** found in ~2.3 seconds
- 78 critical (CORBA, javax.xml.bind, etc.) + 290 high
- Score: **0/100** ("Major Rewrite")
- Effort estimate: **~95 hours (~12 person-days)**
- HTML report renders the gauge + per-area breakdown cleanly. File size: 2.7MB (acceptable).

**Ready to start Phase B4+B5 (Manager Dashboard) or B2 (Runtime SQL/TX analysis) next.**

### Phase B4 + B5 — Manager Dashboard — DONE (2026-05-16)

Shipped as one combined feature since both build on git collection.

**New code (~700 LOC across 8 files):**
- `analyzer/prioritization/GitHistory.java` — churn map model, with `available` flag for graceful degradation
- `GitHistoryCollector.java` — `ProcessBuilder` runs `git log --since=N months ago --name-only`, parses commit-per-file counts. No JGit dependency. 30s timeout, returns empty history on any failure.
- `PrioritizedFinding.java` — wraps a `Finding` with impactScore (0–100), reason text, Effort (LOW/MEDIUM/HIGH), estimated hours, git churn.
- `EffortTable.java` — single source of truth for `Category → minutes` calibration. Used by both scoring and debt calc.
- `ImpactScorer.java` — formula: `severityWeight × confidenceWeight × (1 + log2(1+churn)) + categoryBonus`, clamped to [0,100]. Returns sorted list.
- `TechDebt.java` + `TechDebtCalculator.java` — total hours + dollars at configurable hourly rate, plus per-category breakdown sorted by hours desc.

**Engine wiring:**
- `AnalysisEngine.withHourlyRate()` / `withoutGit()` / `withGitWindowMonths()` builder methods
- After analyzers + suppression: collect git history, score, compute debt, attach to `AnalysisResult`

**CLI flags:**
- `--rate-per-hour <USD>` (default 100)
- `--no-git` (skip git collection)
- `--git-window-months <N>` (default 12)

**Report:**
- New sidebar page **🎯 Priority** with: headline debt card ($273k for Phoenix), git-status pill ("✔ Git-aware: 1393 files"), top-20 priority fixes table (rank, impact bar, severity, issue, file, effort badge, reason), tech-debt-by-category table with % bars
- 2 new Overview KPI cards: **Tech debt** ($), **Top priority fix** (impact score). Both clickable shortcuts to the Priority page.
- New sidebar badge showing count of top-N fixes

**Phoenix validation (with git):**
- Git collection: **1,393 files** with commits in last 12 months, 1.3s
- Tech debt: **$273k / 2,727 hours** at $100/hr, across 9 categories
- Top priority fix: impact score **100/100** — `HIBERNATE_FETCH_STRATEGY: EAGER fetch on single association (JPA default) - heavy entity`
- Report still loads fast, no perf regression

**Differentiator delivered:**
> Top 10 fixes ranked by `severity × confidence × git churn` — the exact list an engineering manager takes to their VP. SonarQube has "hotspots" but doesn't combine these signals. PMD/SpotBugs have no concept of business impact.

**Next options:**
- B2 — Runtime-aware SQL/TX analysis (deeper technical moat)
- B6 — Auto-fix generation (now safe to build with prioritization in place)
- B3 — Architectural / modularity report

### Currency support (small fix, 2026-05-16)
- Default cost currency changed from USD → **EUR**
- New CLI flag: `--currency EUR|USD|GBP|...` (ISO-4217)
- `TechDebt` now carries both `currencyCode` and `currencySymbol` ("€", "$", etc.)
- HTML report uses the symbol consistently (KPI card, priority headline, debt-by-category table)

### Phase B2 — Runtime SQL & Transaction Analysis — DONE (2026-05-16)

Two new analyzers, ~550 LOC combined.

**`SqlQueryAnalyzer`** — inspects every `@Query` / `@NamedQuery` / `@NamedNativeQuery` annotation. Rules:
- `sql.like-leading-wildcard` (HIGH/HIGH) — `LIKE '%foo%'` patterns that prevent index use
- `sql.pagination-without-sort` (HIGH/HIGH) — `Pageable` parameter without `ORDER BY` (unstable pagination under concurrent writes)
- `sql.native-no-typecheck` (MEDIUM/MEDIUM) — native queries returning entities without `resultClass` / `resultSetMapping` (silent breakage on schema changes)
- `sql.in-clause-unbounded` (MEDIUM/MEDIUM) — `IN (:ids)` with no size bound (Oracle 1000-element limit etc.)
- `sql.cartesian-join-suspect` (HIGH/MEDIUM) — multiple FROM roots without explicit JOIN condition

**`TransactionGraphAnalyzer`** — intra-class cross-method analysis on `@Service` / `@Component` / `@Controller` beans. Rules:
- `tx.write-no-transaction` (CRITICAL/MEDIUM) — bean method calls repo writes (`save`, `delete`, ...) but has no `@Transactional` at method or class level
- `tx.long-transaction` (HIGH/MEDIUM) — `@Transactional` method with 5+ repo calls (long-held connection → pool exhaustion)
- `tx.read-not-readonly` (LOW/MEDIUM) — `@Transactional` method with only reads, missing `readOnly = true` (wasted dirty-checking)
- `tx.requires-new-in-loop` (HIGH/HIGH) — call to a `REQUIRES_NEW` method inside a loop body (N transactions instead of 1)

**Differentiator delivered:**
> Neither SonarQube nor PMD/SpotBugs do **cross-method transactional analysis**. They check method-level rules in isolation. The `tx.write-no-transaction` and `tx.long-transaction` rules catch real production bug classes (silent partial writes, connection-pool exhaustion) that those tools miss.

**Phoenix validation:**
- Transaction Graph: **111 findings** (long transactions: up to 25 repo calls per @Transactional method; write-no-tx: 1 group)
- SQL Query: 0 findings on Phoenix (Phoenix uses raw Hibernate sessions + XML named queries, not `@Query`). The analyzer is verified on smaller Spring Data projects.
- Total findings: 10,212 → 10,323 (+111); critical: 5,034 → 5,060 (+26)
- Tech debt: €273k → €278k (long transactions add measurable debt)

### Phase B remaining

- B6 — Auto-fix generation
- B3 — Architectural / modularity report
- B7 — Report modes (dev / lead / exec)

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build all modules (fat JAR via maven-shade-plugin)
mvn clean package

# Quick build, no tests
mvn clean package -q -DskipTests

# Run the analyzer
java -jar analyzer-cli/target/analyzer-cli-0.1.0-SNAPSHOT.jar <PROJECT_PATH> [options]
#   -o, --output DIR   Output directory (default: ./analyzer-output)
#   --json             Generate JSON report
#   --no-html          Skip HTML report
#   -v, --verbose      Print findings to console

# Convenience wrapper (auto-builds if needed)
./analyze.sh /path/to/project
```

Exit codes: 0 = success, 1 = error, 2 = critical findings detected.

## Architecture

Multi-module Maven project (Java 8) — a static analysis CLI tool for legacy Java/Spring/Hibernate codebases.

**Modules:**
- `analyzer-core` — analysis engine, all analyzers, AST parsing, models
- `analyzer-cli` — Picocli CLI entry point (`AnalyzerCli.java`), builds fat JAR
- `analyzer-report` — report generators (console, JSON, self-contained HTML)

**Data flow:**
```
AnalyzerCli → AnalysisEngine → ProjectScanner (JavaParser AST)
                              → AnalyzerRegistry.runAll() → Finding[]
                              → ReportGenerator / HtmlReportGenerator
```

**Key abstractions:**
- `Analyzer` interface — plugin contract. Two overloads: `analyze(List<ParsedSource>)` for AST-only, `analyze(AnalysisContext)` for full project context (non-Java files).
- `AnalyzerRegistry` — fluent builder, runs all registered analyzers, catches per-analyzer exceptions.
- `EntityGraphModel` — builds a directed graph of JPA entities to detect bidirectional EAGER fetch cycles.
- `Finding` — core result object (Category, Severity, title, description, location, suggestion).

**Adding a new analyzer:** implement `Analyzer`, register it in `AnalyzerCli.java`'s registry builder.

## Key Dependencies

- JavaParser 3.25.10 (AST parsing + symbol resolution)
- Picocli 4.7.6 (CLI framework)
- Gson 2.11.0 (JSON serialization)
- SLF4J + Logback (logging)

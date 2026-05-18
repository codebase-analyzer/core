# Integrating SpotBugs and PMD into Codebase Analyzer

This guide shows how to add SpotBugs and PMD as additional analysis modules.
Both have Java APIs that can be invoked programmatically, so their findings
can be merged into the same HTML report.

## SpotBugs Integration

SpotBugs analyzes compiled bytecode (`.class` files), so it requires a prior
`mvn compile` on the target project. It catches: null pointer dereference,
infinite loops, resource leaks, concurrency bugs, and more.

### 1. Add dependency (analyzer-core/pom.xml)

```xml
<dependency>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs</artifactId>
    <version>4.8.6</version>
</dependency>
```

### 2. Create the analyzer

```java
package dev.codeanalyzer.core.analyzer.integration;

import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.analyzer.AnalysisContext;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import edu.umd.cs.findbugs.*;

import java.nio.file.*;
import java.util.*;

public class SpotBugsAnalyzer implements Analyzer {

    @Override
    public String getId() { return "spotbugs"; }

    @Override
    public String getName() { return "SpotBugs"; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        Path projectRoot = context.getProjectRoot();

        // Find compiled classes
        Path classesDir = findClassesDir(projectRoot);
        if (classesDir == null) {
            return findings; // Not compiled, skip
        }

        try {
            Project project = new Project();
            project.addFile(classesDir.toString());

            // Add source dirs for file mapping
            Path srcDir = projectRoot.resolve("src/main/java");
            if (Files.isDirectory(srcDir)) {
                project.addSourceDir(srcDir.toString());
            }

            BugCollectionBugReporter reporter = new BugCollectionBugReporter(project);
            FindBugs2 findBugs = new FindBugs2();
            findBugs.setProject(project);
            findBugs.setBugReporter(reporter);
            findBugs.setDetectorFactoryCollection(
                DetectorFactoryCollection.instance());
            findBugs.execute();

            // Convert SpotBugs results to our Finding model
            for (BugInstance bug : reporter.getBugCollection()) {
                findings.add(convertBug(bug, projectRoot));
            }
        } catch (Exception e) {
            // Log and continue
        }

        return findings;
    }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        return Collections.emptyList(); // needs context
    }

    private Finding convertBug(BugInstance bug, Path projectRoot) {
        // Map SpotBugs priority to our severity
        Finding.Severity severity;
        switch (bug.getPriority()) {
            case 1: severity = Finding.Severity.HIGH; break;
            case 2: severity = Finding.Severity.MEDIUM; break;
            default: severity = Finding.Severity.LOW;
        }

        String file = "";
        int line = 0;
        if (bug.getPrimarySourceLineAnnotation() != null) {
            file = bug.getPrimarySourceLineAnnotation().getSourcePath();
            line = bug.getPrimarySourceLineAnnotation().getStartLine();
        }

        return new Finding(
            Finding.Category.SPRING_CONFIG_SMELL, // or add a SPOTBUGS category
            severity,
            bug.getMessageWithoutPrefix(),
            bug.getBugPattern().getDetailText(),
            SourceLocation.of(file, line, bug.getPrimaryClass().getClassName()),
            "See SpotBugs documentation: https://spotbugs.readthedocs.io/en/latest/bugDescriptions.html#"
                + bug.getType()
        );
    }

    private Path findClassesDir(Path projectRoot) {
        // Maven
        Path target = projectRoot.resolve("target/classes");
        if (Files.isDirectory(target)) return target;
        // Gradle
        Path build = projectRoot.resolve("build/classes/java/main");
        if (Files.isDirectory(build)) return build;
        return null;
    }
}
```

### 3. Register in CLI

```java
// Only if compiled classes exist
AnalyzerRegistry registry = new AnalyzerRegistry()
    .register(new HibernateEntityAnalyzer())
    .register(new SpringBestPracticesAnalyzer())
    .register(new DeadBeanAnalyzer())
    .register(new SpotBugsAnalyzer()); // runs silently if no target/classes
```

---

## PMD Integration

PMD works on source code (no compilation needed), so it runs alongside
your existing analyzers. It catches: unused variables, empty catch blocks,
unnecessary object creation, overly complex methods, copy-paste detection.

### 1. Add dependency

```xml
<dependency>
    <groupId>net.sourceforge.pmd</groupId>
    <artifactId>pmd-java</artifactId>
    <version>7.3.0</version>
</dependency>
```

### 2. Create the analyzer

```java
package dev.codeanalyzer.core.analyzer.integration;

import dev.codeanalyzer.core.analyzer.Analyzer;
import dev.codeanalyzer.core.analyzer.AnalysisContext;
import dev.codeanalyzer.core.model.Finding;
import dev.codeanalyzer.core.model.ParsedSource;
import net.sourceforge.pmd.*;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;

import java.nio.file.*;
import java.util.*;

public class PmdAnalyzer implements Analyzer {

    @Override
    public String getId() { return "pmd"; }

    @Override
    public String getName() { return "PMD"; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();

        try {
            PMDConfiguration config = new PMDConfiguration();
            config.setDefaultLanguageVersion(
                LanguageRegistry.findLanguageByTerseName("java")
                    .getVersion("1.8"));
            config.addRuleSet("category/java/bestpractices.xml");
            config.addRuleSet("category/java/errorprone.xml");
            config.addRuleSet("category/java/performance.xml");
            config.addInputPath(context.getProjectRoot());

            // Exclude test and build dirs
            config.setExcludes(Arrays.asList(
                "*/target/*", "*/build/*", "*/test/*"));

            try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
                Report report = pmd.performAnalysisAndCollectReport();

                for (RuleViolation v : report.getViolations()) {
                    findings.add(new Finding(
                        mapCategory(v.getRule().getRuleSetName()),
                        mapPriority(v.getRule().getPriority()),
                        v.getRule().getName(),
                        v.getDescription(),
                        SourceLocation.of(
                            v.getFilename(), v.getBeginLine(),
                            v.getBeginLine(), "", ""),
                        "PMD Rule: " + v.getRule().getExternalInfoUrl()
                    ));
                }
            }
        } catch (Exception e) {
            // Log and continue
        }

        return findings;
    }

    @Override
    public List<Finding> analyze(List<ParsedSource> sources) {
        return Collections.emptyList();
    }

    private Finding.Severity mapPriority(RulePriority priority) {
        switch (priority) {
            case HIGH: return Finding.Severity.HIGH;
            case MEDIUM_HIGH:
            case MEDIUM: return Finding.Severity.MEDIUM;
            default: return Finding.Severity.LOW;
        }
    }

    private Finding.Category mapCategory(String ruleSet) {
        // Map PMD rule sets to your categories or add a generic one
        return Finding.Category.SPRING_CONFIG_SMELL;
    }
}
```

---

## Recommended Approach

For your project, the fastest path is:

1. **PMD first** — works on source code, no compilation needed, runs in
   the same pipeline as your existing analyzers. Add the dependency, create
   the adapter class above, and you instantly get 300+ rules.

2. **SpotBugs second** — requires `mvn compile` first, but catches bugs
   that source-level analysis misses (null dereference, concurrency issues).
   Add a `--bytecode` flag that enables it when `target/classes` exists.

3. **Keep your custom analyzers as the differentiator** — the Hibernate
   graph analysis, EAGER cycle detection, smart dead bean detection, and
   Spring proxy bypass detection are things neither PMD nor SpotBugs do.
   That's your unique value.

The integration takes about 2-3 hours per tool. All findings flow into
the same `List<Finding>` and appear in the same HTML report with
filtering and severity breakdown.

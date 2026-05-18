package dev.codeanalyzer.core.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.codeanalyzer.core.model.ParsedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks a project's source tree, parses every .java file into an AST,
 * and returns a list of ParsedSource objects ready for analysis.
 */
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final Path projectRoot;
    private final JavaParser javaParser;
    private final AtomicInteger parseErrors = new AtomicInteger(0);

    public ProjectScanner(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.javaParser = createParser();
    }

    private JavaParser createParser() {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        // Add source roots if they exist
        for (String candidate : new String[]{"src/main/java", "src"}) {
            Path srcRoot = projectRoot.resolve(candidate);
            if (Files.isDirectory(srcRoot)) {
                typeSolver.add(new JavaParserTypeSolver(srcRoot));
                log.info("Added source root: {}", srcRoot);
            }
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
                .setSymbolResolver(symbolSolver);

        return new JavaParser(config);
    }

    /**
     * Scans the project and returns all successfully parsed source files.
     */
    public ScanResult scan() throws IOException {
        log.info("Scanning project: {}", projectRoot);
        List<ParsedSource> sources = new ArrayList<>();
        List<Path> javaFiles = collectJavaFiles();

        log.info("Found {} .java files", javaFiles.size());

        for (Path file : javaFiles) {
            try {
                ParseResult<CompilationUnit> result = javaParser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    Path relative = projectRoot.relativize(file);
                    sources.add(new ParsedSource(file, relative, result.getResult().get()));
                } else {
                    parseErrors.incrementAndGet();
                    log.warn("Parse failed for {}: {}", file, result.getProblems());
                }
            } catch (Exception e) {
                parseErrors.incrementAndGet();
                log.warn("Error parsing {}: {}", file, e.getMessage());
            }
        }

        log.info("Parsed {}/{} files successfully ({} errors)",
                sources.size(), javaFiles.size(), parseErrors.get());

        return new ScanResult(sources, javaFiles.size(), parseErrors.get());
    }

    private List<Path> collectJavaFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                // Skip build output, test, hidden dirs, generated sources
                if (dirName.equals("target") || dirName.equals("build")
                        || dirName.equals("node_modules") || dirName.startsWith(".")
                        || dirName.equals("generated-sources")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Cannot access file: {}", file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Result of scanning a project.
     */
    public static class ScanResult {
        private final List<ParsedSource> sources;
        private final int totalFiles;
        private final int parseErrors;

        public ScanResult(List<ParsedSource> sources, int totalFiles, int parseErrors) {
            this.sources = sources;
            this.totalFiles = totalFiles;
            this.parseErrors = parseErrors;
        }

        public List<ParsedSource> getSources() { return sources; }
        public int getTotalFiles() { return totalFiles; }
        public int getParseErrors() { return parseErrors; }
    }
}

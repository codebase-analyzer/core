package dev.codeanalyzer.core.model;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;

/**
 * A parsed Java source file, wrapping the JavaParser AST with file metadata.
 */
public class ParsedSource {

    private final Path filePath;
    private final Path relativePath;
    private final CompilationUnit compilationUnit;

    public ParsedSource(Path filePath, Path relativePath, CompilationUnit compilationUnit) {
        this.filePath = filePath;
        this.relativePath = relativePath;
        this.compilationUnit = compilationUnit;
    }

    public Path getFilePath() { return filePath; }
    public Path getRelativePath() { return relativePath; }
    public CompilationUnit getCompilationUnit() { return compilationUnit; }

    public String getRelativePathString() {
        return relativePath.toString().replace('\\', '/');
    }
}

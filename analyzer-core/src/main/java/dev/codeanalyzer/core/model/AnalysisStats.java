package dev.codeanalyzer.core.model;

/**
 * Statistics about the analyzed codebase.
 */
public class AnalysisStats {

    private final int totalFiles;
    private final int parsedFiles;
    private final int parseErrors;
    private final int entityCount;
    private final int repositoryCount;
    private final int serviceCount;
    private final int controllerCount;
    private final long durationMs;

    private AnalysisStats(Builder builder) {
        this.totalFiles = builder.totalFiles;
        this.parsedFiles = builder.parsedFiles;
        this.parseErrors = builder.parseErrors;
        this.entityCount = builder.entityCount;
        this.repositoryCount = builder.repositoryCount;
        this.serviceCount = builder.serviceCount;
        this.controllerCount = builder.controllerCount;
        this.durationMs = builder.durationMs;
    }

    public int getTotalFiles() { return totalFiles; }
    public int getParsedFiles() { return parsedFiles; }
    public int getParseErrors() { return parseErrors; }
    public int getEntityCount() { return entityCount; }
    public int getRepositoryCount() { return repositoryCount; }
    public int getServiceCount() { return serviceCount; }
    public int getControllerCount() { return controllerCount; }
    public long getDurationMs() { return durationMs; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int totalFiles;
        private int parsedFiles;
        private int parseErrors;
        private int entityCount;
        private int repositoryCount;
        private int serviceCount;
        private int controllerCount;
        private long durationMs;

        public Builder totalFiles(int v) { this.totalFiles = v; return this; }
        public Builder parsedFiles(int v) { this.parsedFiles = v; return this; }
        public Builder parseErrors(int v) { this.parseErrors = v; return this; }
        public Builder entityCount(int v) { this.entityCount = v; return this; }
        public Builder repositoryCount(int v) { this.repositoryCount = v; return this; }
        public Builder serviceCount(int v) { this.serviceCount = v; return this; }
        public Builder controllerCount(int v) { this.controllerCount = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public AnalysisStats build() { return new AnalysisStats(this); }
    }
}

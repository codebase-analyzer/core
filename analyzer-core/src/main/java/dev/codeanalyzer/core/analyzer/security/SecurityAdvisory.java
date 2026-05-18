package dev.codeanalyzer.core.analyzer.security;

final class SecurityAdvisory {

    private final String cveId;
    private final String groupId;
    private final String artifactId;
    private final String affectedRange;
    private final String fixedVersion;
    private final double cvssScore;
    private final String severity;
    private final String summary;

    SecurityAdvisory(String cveId, String groupId, String artifactId,
                     String affectedRange, String fixedVersion,
                     double cvssScore, String severity, String summary) {
        this.cveId = cveId;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.affectedRange = affectedRange;
        this.fixedVersion = fixedVersion;
        this.cvssScore = cvssScore;
        this.severity = severity;
        this.summary = summary;
    }

    String getCveId() { return cveId; }
    String getGroupId() { return groupId; }
    String getArtifactId() { return artifactId; }
    String getAffectedRange() { return affectedRange; }
    String getFixedVersion() { return fixedVersion; }
    double getCvssScore() { return cvssScore; }
    String getSeverity() { return severity; }
    String getSummary() { return summary; }

    String getCoordinates() {
        return groupId + ":" + artifactId;
    }
}

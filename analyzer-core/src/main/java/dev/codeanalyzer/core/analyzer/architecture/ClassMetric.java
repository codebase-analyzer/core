package dev.codeanalyzer.core.analyzer.architecture;

/**
 * Per-class complexity metrics used for god-class detection.
 *
 * <p>We track:
 * <ul>
 *   <li>{@code methodCount} — public + private declared methods (excludes inherited)</li>
 *   <li>{@code fieldCount} — declared fields (state)</li>
 *   <li>{@code linesOfCode} — physical LOC from {@code endLine - beginLine}</li>
 *   <li>{@code outgoingDependencies} — distinct types referenced (rough fan-out)</li>
 *   <li>{@code stereotype} — Spring stereotype if any ({@code Service}, {@code Controller}, etc.)</li>
 * </ul>
 *
 * <p>The triggering thresholds and scoring weights live in
 * {@link ArchitectureAnalyzer}; this class is a pure value object.
 */
public class ClassMetric {

    private final String className;
    private final String packageName;
    private final String filePath;
    private final int beginLine;
    private final int methodCount;
    private final int fieldCount;
    private final int linesOfCode;
    private final int outgoingDependencies;
    private final String stereotype; // null if no @Service/@Controller/@Repository/@Component

    public ClassMetric(String className, String packageName, String filePath,
                       int beginLine, int methodCount, int fieldCount,
                       int linesOfCode, int outgoingDependencies, String stereotype) {
        this.className = className;
        this.packageName = packageName;
        this.filePath = filePath;
        this.beginLine = beginLine;
        this.methodCount = methodCount;
        this.fieldCount = fieldCount;
        this.linesOfCode = linesOfCode;
        this.outgoingDependencies = outgoingDependencies;
        this.stereotype = stereotype;
    }

    public String getClassName() { return className; }
    public String getPackageName() { return packageName; }
    public String getFilePath() { return filePath; }
    public int getBeginLine() { return beginLine; }
    public int getMethodCount() { return methodCount; }
    public int getFieldCount() { return fieldCount; }
    public int getLinesOfCode() { return linesOfCode; }
    public int getOutgoingDependencies() { return outgoingDependencies; }
    public String getStereotype() { return stereotype; }

    /**
     * Composite weight used for ranking god classes. A class that's big AND
     * heavily coupled AND a Spring stereotype is more dangerous than one
     * that's only big.
     */
    public int weight() {
        int w = methodCount * 2 + linesOfCode / 50 + outgoingDependencies * 3 + fieldCount;
        if (stereotype != null) w = (int) (w * 1.3); // Spring stereotype amplifies impact
        return w;
    }
}

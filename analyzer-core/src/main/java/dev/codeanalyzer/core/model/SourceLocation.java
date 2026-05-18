package dev.codeanalyzer.core.model;

/**
 * Points to a specific location in the source code.
 */
public class SourceLocation {

    private final String filePath;
    private final int startLine;
    private final int endLine;
    private final String className;
    private final String memberName;

    public SourceLocation(String filePath, int startLine, int endLine,
                          String className, String memberName) {
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.className = className;
        this.memberName = memberName;
    }

    public static SourceLocation of(String filePath, int line, String className) {
        return new SourceLocation(filePath, line, line, className, null);
    }

    public static SourceLocation of(String filePath, int startLine, int endLine,
                                     String className, String memberName) {
        return new SourceLocation(filePath, startLine, endLine, className, memberName);
    }

    public String getFilePath() { return filePath; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getClassName() { return className; }
    public String getMemberName() { return memberName; }

    @Override
    public String toString() {
        String loc = filePath + ":" + startLine;
        if (memberName != null) {
            loc += " (" + className + "#" + memberName + ")";
        } else if (className != null) {
            loc += " (" + className + ")";
        }
        return loc;
    }
}

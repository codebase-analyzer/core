package dev.codeanalyzer.core.analyzer.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MavenVersionComparator {

    private static final Pattern RANGE_PATTERN = Pattern.compile(
        "^([\\[\\(])\\s*([^,]*?)\\s*,\\s*([^\\]\\)]*?)\\s*([\\]\\)])$");

    private MavenVersionComparator() {}

    static boolean isInRange(String version, String range) {
        if (range == null || version == null) return false;
        range = range.trim();
        version = version.trim();

        if (range.contains(",")) {
            Matcher m = RANGE_PATTERN.matcher(range);
            if (!m.matches()) return false;

            boolean lowerInclusive = "[".equals(m.group(1));
            String lowerBound = m.group(2);
            String upperBound = m.group(3);
            boolean upperInclusive = "]".equals(m.group(4));

            if (!lowerBound.isEmpty()) {
                int cmp = compare(version, lowerBound);
                if (lowerInclusive ? cmp < 0 : cmp <= 0) return false;
            }

            if (!upperBound.isEmpty()) {
                int cmp = compare(version, upperBound);
                if (upperInclusive ? cmp > 0 : cmp >= 0) return false;
            }

            return true;
        }

        // Exact version match
        return compare(version, range) == 0;
    }

    static int compare(String v1, String v2) {
        int[] parts1 = parseVersion(v1);
        int[] parts2 = parseVersion(v2);

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parts1[i] : 0;
            int p2 = i < parts2.length ? parts2[i] : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        // Strip qualifiers like -RELEASE, -SNAPSHOT, .RELEASE, .Final etc.
        String cleaned = version.replaceAll("[-.](RELEASE|SNAPSHOT|Final|GA|SP\\d+|RC\\d+|M\\d+|beta\\d*|alpha\\d*|CR\\d+)$", "");
        // Handle versions like "2.0-beta9" -> "2.0.0"
        cleaned = cleaned.replaceAll("-.*$", "");
        String[] segments = cleaned.split("\\.");
        int[] result = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                result[i] = Integer.parseInt(segments[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}

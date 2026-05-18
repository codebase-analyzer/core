package dev.codeanalyzer.core.suppression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny YAML-subset reader for our suppression config.
 *
 * <p>Supports only the flat "key with list-of-strings" shape:
 * <pre>
 * key1:
 *   - value1
 *   - "value 2"
 * key2:
 *   - value3
 * </pre>
 *
 * <p>Comments ({@code # ...}) and blank lines are ignored. Quotes (single or
 * double) around list items are stripped. This is deliberately not a full
 * YAML parser — keeping it dep-free is worth more than supporting nested maps
 * we don't currently need.
 */
final class MiniYaml {

    private MiniYaml() {}

    /**
     * Parses the document into a map of key → list of string values.
     * Unknown structure is silently dropped (forgiving).
     */
    static Map<String, List<String>> parseListsOfStrings(String yaml) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (yaml == null || yaml.isEmpty()) return out;

        String currentKey = null;
        List<String> currentList = null;

        for (String rawLine : yaml.split("\\r?\\n")) {
            String line = stripComment(rawLine);
            if (line.trim().isEmpty()) continue;

            // A line with no leading whitespace and a trailing colon is a key.
            if (!Character.isWhitespace(line.charAt(0)) && line.endsWith(":")) {
                currentKey = line.substring(0, line.length() - 1).trim();
                currentList = new ArrayList<>();
                out.put(currentKey, currentList);
                continue;
            }

            // A list item under the current key.
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") && currentList != null) {
                String item = trimmed.substring(2).trim();
                out.get(currentKey).add(unquote(item));
            }
        }
        return out;
    }

    private static String stripComment(String line) {
        // Cheap comment strip — doesn't handle '#' inside quoted strings, which
        // we don't need for the limited config shape we support.
        int idx = line.indexOf('#');
        if (idx < 0) return line;
        // Preserve '#' inside quoted strings (best-effort).
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < idx; i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!inQuote) { inQuote = true; quoteChar = c; }
                else if (quoteChar == c) inQuote = false;
            }
        }
        if (inQuote) return line; // hash was inside a quote; keep the line as-is
        return line.substring(0, idx);
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}

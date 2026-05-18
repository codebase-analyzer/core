package dev.codeanalyzer.core.analyzer.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * Rich suggestions with before/after code examples for known PMD rules.
 * Uses the markdown-like format supported by HtmlReportGenerator.formatSuggestion():
 * ### for headers, ``` for code blocks, - for list items.
 */
final class PmdSuggestions {

    private static final Map<String, String> SUGGESTIONS = new HashMap<>();

    static {
        SUGGESTIONS.put("AvoidDuplicateLiterals",
            "### Problem\n" +
            "Duplicated string literals scattered across the code make refactoring error-prone and violate DRY.\n" +
            "### Bad\n" +
            "```\n" +
            "if (status.equals(\"ACTIVE\")) { ... }\n" +
            "log.info(\"User status: ACTIVE\");\n" +
            "query.setParameter(\"status\", \"ACTIVE\");\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "private static final String STATUS_ACTIVE = \"ACTIVE\";\n" +
            "\n" +
            "if (status.equals(STATUS_ACTIVE)) { ... }\n" +
            "log.info(\"User status: \" + STATUS_ACTIVE);\n" +
            "query.setParameter(\"status\", STATUS_ACTIVE);\n" +
            "```\n");

        SUGGESTIONS.put("AvoidFileStream",
            "### Problem\n" +
            "FileInputStream/FileOutputStream use finalizers which cause GC pressure and can leak resources.\n" +
            "### Bad\n" +
            "```\n" +
            "InputStream in = new FileInputStream(file);\n" +
            "OutputStream out = new FileOutputStream(file);\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "InputStream in = Files.newInputStream(file.toPath());\n" +
            "OutputStream out = Files.newOutputStream(file.toPath());\n" +
            "// Or with options:\n" +
            "OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE);\n" +
            "```\n");

        SUGGESTIONS.put("UnusedPrivateField",
            "### Problem\n" +
            "Private fields that are never read waste memory and confuse readers.\n" +
            "### Bad\n" +
            "```\n" +
            "public class OrderService {\n" +
            "    private final Logger log = LoggerFactory.getLogger(getClass());\n" +
            "    private int retryCount; // never read\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "public class OrderService {\n" +
            "    private final Logger log = LoggerFactory.getLogger(getClass());\n" +
            "    // retryCount removed — it was never used\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("UnusedPrivateMethod",
            "### Problem\n" +
            "Dead private methods add noise and maintenance cost.\n" +
            "### Bad\n" +
            "```\n" +
            "public class ReportGenerator {\n" +
            "    public void generate() { ... }\n" +
            "\n" +
            "    private String formatDate(Date d) { // never called\n" +
            "        return new SimpleDateFormat(\"yyyy-MM-dd\").format(d);\n" +
            "    }\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "Remove the method entirely. If needed later, recover it from version control.\n");

        SUGGESTIONS.put("UnusedLocalVariable",
            "### Problem\n" +
            "Variables that are assigned but never read indicate dead code or a missing operation.\n" +
            "### Bad\n" +
            "```\n" +
            "String result = service.process(order);\n" +
            "// result is never used — was the return value meant to be checked?\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "// Either use the result:\n" +
            "String result = service.process(order);\n" +
            "log.info(\"Processed: {}\", result);\n" +
            "\n" +
            "// Or remove the assignment if the side effect is all you need:\n" +
            "service.process(order);\n" +
            "```\n");

        SUGGESTIONS.put("EmptyCatchBlock",
            "### Problem\n" +
            "Swallowing exceptions silently hides bugs and makes debugging impossible.\n" +
            "### Bad\n" +
            "```\n" +
            "try {\n" +
            "    connection.close();\n" +
            "} catch (SQLException e) {\n" +
            "    // empty\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "try {\n" +
            "    connection.close();\n" +
            "} catch (SQLException e) {\n" +
            "    log.warn(\"Failed to close connection\", e);\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("AvoidReassigningParameters",
            "### Problem\n" +
            "Reassigning a method parameter makes the code harder to follow — the reader loses track of the original input.\n" +
            "### Bad\n" +
            "```\n" +
            "public String normalize(String input) {\n" +
            "    input = input.trim();\n" +
            "    input = input.toLowerCase();\n" +
            "    return input;\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "public String normalize(String input) {\n" +
            "    String normalized = input.trim();\n" +
            "    return normalized.toLowerCase();\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("CloseResource",
            "### Problem\n" +
            "Resources that are not closed in a finally block or try-with-resources can leak connections, file handles, etc.\n" +
            "### Bad\n" +
            "```\n" +
            "Connection conn = dataSource.getConnection();\n" +
            "Statement stmt = conn.createStatement();\n" +
            "ResultSet rs = stmt.executeQuery(sql);\n" +
            "// if an exception occurs, resources are never closed\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "try (Connection conn = dataSource.getConnection();\n" +
            "     Statement stmt = conn.createStatement();\n" +
            "     ResultSet rs = stmt.executeQuery(sql)) {\n" +
            "    // resources auto-closed even on exception\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("UseUtilityClass",
            "### Problem\n" +
            "A class with only static methods can be instantiated for no reason.\n" +
            "### Bad\n" +
            "```\n" +
            "public class StringUtils {\n" +
            "    public static String capitalize(String s) { ... }\n" +
            "}\n" +
            "// new StringUtils() compiles but is meaningless\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "public final class StringUtils {\n" +
            "    private StringUtils() {} // prevent instantiation\n" +
            "    public static String capitalize(String s) { ... }\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("AvoidReassigningLoopVariables",
            "### Problem\n" +
            "Modifying a loop variable inside the loop body makes the iteration unpredictable.\n" +
            "### Bad\n" +
            "```\n" +
            "for (int i = 0; i < list.size(); i++) {\n" +
            "    if (shouldSkip(list.get(i))) {\n" +
            "        i++; // skips next element — easy to miss\n" +
            "    }\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "for (int i = 0; i < list.size(); i++) {\n" +
            "    if (shouldSkip(list.get(i))) {\n" +
            "        continue;\n" +
            "    }\n" +
            "    process(list.get(i));\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("SimplifyBooleanReturns",
            "### Problem\n" +
            "Returning true/false from an if/else that tests a boolean is redundant.\n" +
            "### Bad\n" +
            "```\n" +
            "if (user.isActive()) {\n" +
            "    return true;\n" +
            "} else {\n" +
            "    return false;\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "return user.isActive();\n" +
            "```\n");

        SUGGESTIONS.put("LooseCoupling",
            "### Problem\n" +
            "Declaring variables with concrete collection types locks you into an implementation.\n" +
            "### Bad\n" +
            "```\n" +
            "private ArrayList<Order> orders = new ArrayList<>();\n" +
            "public HashMap<String, User> getUsers() { ... }\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "private List<Order> orders = new ArrayList<>();\n" +
            "public Map<String, User> getUsers() { ... }\n" +
            "```\n");

        SUGGESTIONS.put("InefficientStringBuffering",
            "### Problem\n" +
            "String concatenation inside a StringBuilder.append() defeats the purpose of using StringBuilder.\n" +
            "### Bad\n" +
            "```\n" +
            "sb.append(\"Name: \" + user.getName());\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "sb.append(\"Name: \").append(user.getName());\n" +
            "```\n");

        SUGGESTIONS.put("UseStringBufferForStringAppends",
            "### Problem\n" +
            "Repeated string concatenation in a loop creates a new String object each time.\n" +
            "### Bad\n" +
            "```\n" +
            "String result = \"\";\n" +
            "for (String item : items) {\n" +
            "    result += item + \", \";\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "StringBuilder result = new StringBuilder();\n" +
            "for (String item : items) {\n" +
            "    result.append(item).append(\", \");\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("ConsecutiveLiteralAppends",
            "### Problem\n" +
            "Multiple consecutive appends of string literals can be merged into one.\n" +
            "### Bad\n" +
            "```\n" +
            "sb.append(\"<tr>\");\n" +
            "sb.append(\"<td>\");\n" +
            "sb.append(\"</td>\");\n" +
            "sb.append(\"</tr>\");\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "sb.append(\"<tr><td></td></tr>\");\n" +
            "```\n");

        SUGGESTIONS.put("MissingOverride",
            "### Problem\n" +
            "Without @Override, a typo in the method signature creates a new method instead of overriding.\n" +
            "### Bad\n" +
            "```\n" +
            "public boolean equals(MyClass other) { // not an override!\n" +
            "    return this.id == other.id;\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "@Override\n" +
            "public boolean equals(Object other) {\n" +
            "    if (!(other instanceof MyClass)) return false;\n" +
            "    return this.id == ((MyClass) other).id;\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("CompareObjectsWithEquals",
            "### Problem\n" +
            "Using == on objects compares references, not values.\n" +
            "### Bad\n" +
            "```\n" +
            "if (user.getStatus() == \"ACTIVE\") { ... }\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "if (\"ACTIVE\".equals(user.getStatus())) { ... }\n" +
            "```\n");

        SUGGESTIONS.put("AvoidInstantiatingObjectsInLoops",
            "### Problem\n" +
            "Creating objects inside a loop when they could be reused causes unnecessary GC pressure.\n" +
            "### Bad\n" +
            "```\n" +
            "for (String date : dates) {\n" +
            "    SimpleDateFormat fmt = new SimpleDateFormat(\"yyyy-MM-dd\");\n" +
            "    results.add(fmt.parse(date));\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "SimpleDateFormat fmt = new SimpleDateFormat(\"yyyy-MM-dd\");\n" +
            "for (String date : dates) {\n" +
            "    results.add(fmt.parse(date));\n" +
            "}\n" +
            "```\n");

        SUGGESTIONS.put("InefficientEmptyStringCheck",
            "### Problem\n" +
            "Comparing string length or using equals(\"\") is less readable and sometimes slower than isEmpty().\n" +
            "### Bad\n" +
            "```\n" +
            "if (name.length() == 0) { ... }\n" +
            "if (name.equals(\"\")) { ... }\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "if (name.isEmpty()) { ... }\n" +
            "```\n");

        SUGGESTIONS.put("UselessStringValueOf",
            "### Problem\n" +
            "Calling String.valueOf() on a value already being concatenated with a string is unnecessary.\n" +
            "### Bad\n" +
            "```\n" +
            "String msg = \"Count: \" + String.valueOf(count);\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "String msg = \"Count: \" + count;\n" +
            "```\n");

        SUGGESTIONS.put("SimplifyConditional",
            "### Problem\n" +
            "Overly complex boolean expressions can be simplified for readability.\n" +
            "### Bad\n" +
            "```\n" +
            "if (!(x != y)) { ... }\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "if (x == y) { ... }\n" +
            "```\n");

        SUGGESTIONS.put("UnusedImports",
            "### Problem\n" +
            "Unused imports clutter the file and can cause confusion about actual dependencies.\n" +
            "### Fix\n" +
            "Remove the unused import. Most IDEs can do this automatically (Ctrl+Shift+O in Eclipse, Ctrl+Alt+O in IntelliJ).\n");

        SUGGESTIONS.put("UnnecessaryLocalBeforeReturn",
            "### Problem\n" +
            "Assigning to a local variable only to immediately return it adds noise.\n" +
            "### Bad\n" +
            "```\n" +
            "public String getName() {\n" +
            "    String name = user.getFirstName() + \" \" + user.getLastName();\n" +
            "    return name;\n" +
            "}\n" +
            "```\n" +
            "### Good\n" +
            "```\n" +
            "public String getName() {\n" +
            "    return user.getFirstName() + \" \" + user.getLastName();\n" +
            "}\n" +
            "```\n");
    }

    private PmdSuggestions() {}

    static String getSuggestion(String ruleName, String defaultDescription) {
        String rich = SUGGESTIONS.get(ruleName);
        if (rich != null) {
            return rich;
        }
        return "PMD [" + ruleName + "]: " + defaultDescription;
    }
}

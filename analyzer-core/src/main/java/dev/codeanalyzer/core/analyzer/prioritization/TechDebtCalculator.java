package dev.codeanalyzer.core.analyzer.prioritization;

import dev.codeanalyzer.core.model.Finding;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a finding list into a {@link TechDebt} projection by mapping each
 * finding to its category's estimated minutes (see {@link EffortTable}) and
 * multiplying by the configurable hourly rate.
 *
 * <p>Returned {@code byCategory} map is sorted by hours descending so the
 * report can show the biggest debt drivers first.
 */
public final class TechDebtCalculator {

    private TechDebtCalculator() {}

    /** Backward-compat — defaults to EUR. */
    public static TechDebt calculate(List<Finding> findings, double hourlyRate) {
        return calculate(findings, hourlyRate, "EUR");
    }

    public static TechDebt calculate(List<Finding> findings, double hourlyRate, String currencyCode) {
        if (findings == null || findings.isEmpty()) return TechDebt.empty();

        String code = currencyCode != null ? currencyCode.toUpperCase() : "EUR";
        String symbol = symbolFor(code);

        Map<Finding.Category, int[]> minutesByCategory = new EnumMap<>(Finding.Category.class);
        Map<Finding.Category, Integer> countByCategory = new EnumMap<>(Finding.Category.class);

        int totalMinutes = 0;
        for (Finding f : findings) {
            int m = EffortTable.minutesFor(f.getCategory());
            totalMinutes += m;
            minutesByCategory.computeIfAbsent(f.getCategory(), k -> new int[1])[0] += m;
            countByCategory.merge(f.getCategory(), 1, Integer::sum);
        }

        int totalHours = (int) Math.round(totalMinutes / 60.0);
        double totalCost = totalHours * hourlyRate;

        Map<Finding.Category, TechDebt.CategoryDebt> sorted = new LinkedHashMap<>();
        minutesByCategory.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> {
                    Finding.Category cat = entry.getKey();
                    int hours = (int) Math.round(entry.getValue()[0] / 60.0);
                    double cost = hours * hourlyRate;
                    int count = countByCategory.getOrDefault(cat, 0);
                    sorted.put(cat, new TechDebt.CategoryDebt(cat, count, hours, cost));
                });

        return new TechDebt(totalHours, totalCost, hourlyRate, code, symbol, sorted);
    }

    /** ISO-4217 → display symbol for the currencies we expect to see. */
    private static String symbolFor(String code) {
        switch (code) {
            case "EUR": return "€"; // €
            case "USD": return "$";
            case "GBP": return "£"; // £
            case "JPY": return "¥"; // ¥
            case "CHF": return "CHF ";
            case "SEK": case "NOK": case "DKK": return code + " ";
            case "CAD": return "CA$";
            case "AUD": return "A$";
            default:    return code + " ";   // fallback: use the code itself
        }
    }
}

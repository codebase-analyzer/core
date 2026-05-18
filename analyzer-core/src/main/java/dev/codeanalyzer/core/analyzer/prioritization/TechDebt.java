package dev.codeanalyzer.core.analyzer.prioritization;

import dev.codeanalyzer.core.model.Finding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregated debt projection (hours + dollars), overall and per category. */
public final class TechDebt {

    private final int totalHours;
    private final double totalCost;
    private final double hourlyRate;
    private final String currencyCode;     // ISO-4217 like "EUR", "USD"
    private final String currencySymbol;   // "€", "$", "£", ...
    private final Map<Finding.Category, CategoryDebt> byCategory;

    public TechDebt(int totalHours, double totalCost, double hourlyRate,
                    String currencyCode, String currencySymbol,
                    Map<Finding.Category, CategoryDebt> byCategory) {
        this.totalHours = totalHours;
        this.totalCost = totalCost;
        this.hourlyRate = hourlyRate;
        this.currencyCode = currencyCode != null ? currencyCode : "EUR";
        this.currencySymbol = currencySymbol != null ? currencySymbol : "€"; // €
        this.byCategory = Collections.unmodifiableMap(new LinkedHashMap<>(byCategory));
    }

    public static TechDebt empty() {
        return new TechDebt(0, 0.0, 0.0, "EUR", "€",
                Collections.<Finding.Category, CategoryDebt>emptyMap());
    }

    public int getTotalHours()   { return totalHours; }
    /** Total projected cost in the configured currency. */
    public double getTotalCost() { return totalCost; }
    public double getHourlyRate()   { return hourlyRate; }
    public String getCurrencyCode()   { return currencyCode; }
    public String getCurrencySymbol() { return currencySymbol; }
    public Map<Finding.Category, CategoryDebt> getByCategory() { return byCategory; }

    public boolean isEmpty() { return totalHours == 0; }

    /** Per-category breakdown row. */
    public static final class CategoryDebt {
        public final Finding.Category category;
        public final int findings;
        public final int hours;
        public final double cost;
        public CategoryDebt(Finding.Category category, int findings, int hours, double cost) {
            this.category = category;
            this.findings = findings;
            this.hours = hours;
            this.cost = cost;
        }
    }
}

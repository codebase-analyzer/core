package dev.codeanalyzer.core.analyzer.security;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SecurityAdvisoryDatabase {

    private static final Logger log = LoggerFactory.getLogger(SecurityAdvisoryDatabase.class);
    private static final String RESOURCE_PATH = "/security-advisories.json";

    private final Map<String, List<SecurityAdvisory>> advisoriesByCoordinates = new HashMap<>();

    SecurityAdvisoryDatabase() {
        loadAdvisories();
    }

    private void loadAdvisories() {
        InputStream is = getClass().getResourceAsStream(RESOURCE_PATH);
        if (is == null) {
            log.warn("Security advisory database not found at {}", RESOURCE_PATH);
            return;
        }

        try {
            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            Type listType = new TypeToken<List<SecurityAdvisory>>() {}.getType();
            List<SecurityAdvisory> advisories = gson.fromJson(reader, listType);
            reader.close();

            for (SecurityAdvisory advisory : advisories) {
                String key = advisory.getCoordinates();
                advisoriesByCoordinates.computeIfAbsent(key, k -> new ArrayList<>()).add(advisory);
            }

            log.info("Loaded {} security advisories for {} artifacts",
                advisories.size(), advisoriesByCoordinates.size());
        } catch (Exception e) {
            log.warn("Failed to load security advisories: {}", e.getMessage());
        }
    }

    List<SecurityAdvisory> findAdvisories(String groupArtifact, String version) {
        if (version == null || version.isEmpty() || version.contains("${")) {
            return Collections.emptyList();
        }

        List<SecurityAdvisory> candidates = advisoriesByCoordinates.get(groupArtifact);
        if (candidates == null) {
            return Collections.emptyList();
        }

        List<SecurityAdvisory> matched = new ArrayList<>();
        for (SecurityAdvisory advisory : candidates) {
            if (MavenVersionComparator.isInRange(version, advisory.getAffectedRange())) {
                matched.add(advisory);
            }
        }
        return matched;
    }

    int getTotalAdvisoryCount() {
        int count = 0;
        for (List<SecurityAdvisory> list : advisoriesByCoordinates.values()) {
            count += list.size();
        }
        return count;
    }
}

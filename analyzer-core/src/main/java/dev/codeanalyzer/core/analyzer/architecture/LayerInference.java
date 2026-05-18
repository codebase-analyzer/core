package dev.codeanalyzer.core.analyzer.architecture;

/**
 * Convention-based layer inference for Java packages. Maps a fully qualified
 * package name to one of the canonical layers we care about, or {@code null}
 * if no convention matches.
 *
 * <p>We prefer the most specific (rightmost) layer-like token so:
 * <pre>
 *   com.acme.userservice.web.controller -> CONTROLLER
 *   com.acme.user.service.impl          -> SERVICE
 *   com.acme.persistence.repository     -> REPOSITORY
 *   com.acme.model.user                 -> ENTITY (only if class is @Entity, decided at analyzer level)
 * </pre>
 *
 * <p>These conventions cover ~95% of Spring projects in the wild. If a
 * codebase uses radically different naming (e.g. DDD with {@code application},
 * {@code domain}, {@code infrastructure}), we'll return {@code UNKNOWN} for
 * most packages and only fire layering findings where the convention matches.
 * That's intentional — better silent than wrong.
 */
public final class LayerInference {

    public enum Layer {
        CONTROLLER, // web/REST entry points
        SERVICE,    // business logic
        REPOSITORY, // persistence access
        ENTITY,     // JPA entities / domain models
        DTO,        // transfer objects
        CONFIG,     // @Configuration classes
        UTIL,       // helpers, infrastructure
        UNKNOWN
    }

    private LayerInference() {}

    /**
     * Infer the layer for a package using package-name tokens.
     * Scans tokens right-to-left so the most specific (innermost) hint wins.
     */
    public static Layer of(String packageName) {
        if (packageName == null || packageName.isEmpty()) return Layer.UNKNOWN;
        String[] tokens = packageName.toLowerCase().split("\\.");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String t = tokens[i];
            // Controller layer
            if (t.equals("controller") || t.equals("controllers")
                    || t.equals("rest") || t.equals("api")
                    || t.equals("web") || t.equals("endpoint") || t.equals("endpoints")
                    || t.equals("resource") || t.equals("resources")
                    || t.equals("action") || t.equals("actions")) {
                return Layer.CONTROLLER;
            }
            // Service layer
            if (t.equals("service") || t.equals("services")
                    || t.equals("business") || t.equals("bo")
                    || t.equals("usecase") || t.equals("usecases")
                    || t.equals("manager") || t.equals("managers")) {
                return Layer.SERVICE;
            }
            // Repository / DAO layer
            if (t.equals("repository") || t.equals("repositories")
                    || t.equals("dao") || t.equals("daos")
                    || t.equals("persistence") || t.equals("persistance") // common typo
                    || t.equals("store") || t.equals("stores")) {
                return Layer.REPOSITORY;
            }
            // Entity / domain layer
            if (t.equals("entity") || t.equals("entities")
                    || t.equals("model") || t.equals("models")
                    || t.equals("domain") || t.equals("po")) {
                return Layer.ENTITY;
            }
            // DTO layer (loose: includes 'vo', 'request', 'response')
            if (t.equals("dto") || t.equals("dtos")
                    || t.equals("vo") || t.equals("vos")
                    || t.equals("request") || t.equals("response")
                    || t.equals("payload") || t.equals("payloads")) {
                return Layer.DTO;
            }
            // Config layer
            if (t.equals("config") || t.equals("configuration") || t.equals("conf")) {
                return Layer.CONFIG;
            }
            // Util / helper layer
            if (t.equals("util") || t.equals("utils")
                    || t.equals("helper") || t.equals("helpers")
                    || t.equals("common") || t.equals("commons")
                    || t.equals("infra") || t.equals("infrastructure")) {
                return Layer.UTIL;
            }
        }
        return Layer.UNKNOWN;
    }

    /**
     * True if a call from layer {@code from} to layer {@code to} violates the
     * conventional onion: Controller -> Service -> Repository -> Entity.
     * Returns {@code null} (i.e. no violation) when either layer is UNKNOWN
     * or the call direction is allowed.
     *
     * <p>Flagged violations:
     * <ul>
     *   <li>CONTROLLER -> REPOSITORY (skipping the service layer)</li>
     *   <li>SERVICE -> CONTROLLER (inverted dependency, breaks layering)</li>
     *   <li>REPOSITORY -> SERVICE (inverted dependency)</li>
     *   <li>REPOSITORY -> CONTROLLER (severely inverted)</li>
     *   <li>ENTITY -> SERVICE / REPOSITORY / CONTROLLER (domain pollution)</li>
     * </ul>
     */
    public static String violationReason(Layer from, Layer to) {
        if (from == null || to == null) return null;
        if (from == Layer.UNKNOWN || to == Layer.UNKNOWN) return null;

        if (from == Layer.CONTROLLER && to == Layer.REPOSITORY) {
            return "Controller bypasses the service layer and calls a repository directly.";
        }
        if (from == Layer.SERVICE && to == Layer.CONTROLLER) {
            return "Service depends on a controller — inverts the standard request flow.";
        }
        if (from == Layer.REPOSITORY && to == Layer.SERVICE) {
            return "Repository depends on a service — inverts the persistence boundary.";
        }
        if (from == Layer.REPOSITORY && to == Layer.CONTROLLER) {
            return "Repository depends on a controller — severe layering inversion.";
        }
        if (from == Layer.ENTITY && (to == Layer.SERVICE || to == Layer.REPOSITORY || to == Layer.CONTROLLER)) {
            return "Entity/domain depends on the " + to.name().toLowerCase()
                    + " layer — couples the domain model to application code.";
        }
        return null;
    }
}

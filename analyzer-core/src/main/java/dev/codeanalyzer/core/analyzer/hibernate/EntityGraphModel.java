package dev.codeanalyzer.core.analyzer.hibernate;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import dev.codeanalyzer.core.model.ParsedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds and holds the full entity relationship graph.
 * Used by the analyzer to score findings based on actual risk,
 * not just annotation presence.
 */
public class EntityGraphModel {

    private static final Logger log = LoggerFactory.getLogger(EntityGraphModel.class);

    private final Map<String, EntityInfo> entities = new LinkedHashMap<>();

    /**
     * Build the graph from all parsed sources.
     */
    public void build(List<ParsedSource> sources) {
        // Pass 1: discover all entities and their basic info
        for (ParsedSource source : sources) {
            for (ClassOrInterfaceDeclaration clazz : source.getCompilationUnit()
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                if (clazz.getAnnotationByName("Entity").isPresent()) {
                    EntityInfo info = buildEntityInfo(clazz, source);
                    entities.put(info.getName(), info);
                }
            }
        }

        // Pass 2: resolve target entity references in relationships
        for (EntityInfo entity : entities.values()) {
            for (RelationInfo rel : entity.getRelations()) {
                EntityInfo target = entities.get(rel.getTargetTypeName());
                if (target != null) {
                    rel.setResolvedTarget(target);
                }
            }
        }

        log.info("Entity graph: {} entities, {} total relations",
                entities.size(),
                entities.values().stream().mapToInt(e -> e.getRelations().size()).sum());
    }

    public EntityInfo getEntity(String name) {
        return entities.get(name);
    }

    public Collection<EntityInfo> getAllEntities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    /**
     * Detect bidirectional EAGER cycles.
     * A -> B (EAGER) and B -> A (EAGER) = cycle.
     * Also detects transitive: A -> B (EAGER) -> C (EAGER) -> A (EAGER).
     */
    public List<EagerCycle> detectEagerCycles() {
        List<EagerCycle> cycles = new ArrayList<>();

        for (EntityInfo entity : entities.values()) {
            detectCyclesDfs(entity, new LinkedList<CyclePath>(), new HashSet<String>(), cycles);
        }
        return cycles;
    }

    private void detectCyclesDfs(EntityInfo current, LinkedList<CyclePath> path,
                                  Set<String> pathNames, List<EagerCycle> cycles) {
        if (pathNames.contains(current.getName())) {
            // Found a cycle — extract the portion from the cycle start
            for (int i = 0; i < path.size(); i++) {
                if (path.get(i).entity.getName().equals(current.getName())) {
                    List<CyclePath> cyclePath = new ArrayList<>(path.subList(i, path.size()));
                    cycles.add(new EagerCycle(cyclePath));
                    return;
                }
            }
            return;
        }

        pathNames.add(current.getName());

        for (RelationInfo rel : current.getRelations()) {
            if (rel.isEffectivelyEager() && rel.getResolvedTarget() != null) {
                path.addLast(new CyclePath(current, rel));
                detectCyclesDfs(rel.getResolvedTarget(), path, pathNames, cycles);
                path.removeLast();
            }
        }

        pathNames.remove(current.getName());
    }

    /**
     * Count how many EAGER associations a target entity transitively pulls in.
     * Depth-limited to avoid infinite recursion on cycles.
     */
    public int countTransitiveEagerDepth(EntityInfo entity, int maxDepth) {
        return countTransitiveEagerDfs(entity, new HashSet<>(), 0, maxDepth);
    }

    private int countTransitiveEagerDfs(EntityInfo entity, Set<String> visited,
                                         int depth, int maxDepth) {
        if (depth >= maxDepth || visited.contains(entity.getName())) {
            return 0;
        }
        visited.add(entity.getName());

        int count = 0;
        for (RelationInfo rel : entity.getRelations()) {
            if (rel.isEffectivelyEager() && rel.getResolvedTarget() != null) {
                count++;
                count += countTransitiveEagerDfs(rel.getResolvedTarget(), visited, depth + 1, maxDepth);
            }
        }
        return count;
    }

    private EntityInfo buildEntityInfo(ClassOrInterfaceDeclaration clazz, ParsedSource source) {
        String name = clazz.getNameAsString();
        int fieldCount = countPersistentFields(clazz);
        List<RelationInfo> relations = new ArrayList<>();

        for (FieldDeclaration field : clazz.getFields()) {
            if (field.getAnnotationByName("Transient").isPresent()
                    || field.isStatic()) {
                continue;
            }

            String fieldName = field.getVariables().get(0).getNameAsString();
            String targetType = resolveTargetType(field);

            for (String relAnn : Arrays.asList("ManyToOne", "OneToOne", "OneToMany", "ManyToMany")) {
                Optional<AnnotationExpr> ann = field.getAnnotationByName(relAnn);
                if (ann.isPresent()) {
                    String fetchType = extractFetchType(ann.get());
                    boolean isCollection = relAnn.equals("OneToMany") || relAnn.equals("ManyToMany");
                    boolean hasBatchSize = field.getAnnotationByName("BatchSize").isPresent();
                    boolean hasFetchAnnotation = field.getAnnotationByName("Fetch").isPresent();
                    int line = field.getBegin().map(p -> p.line).orElse(0);

                    relations.add(new RelationInfo(
                            fieldName, relAnn, targetType, fetchType,
                            isCollection, hasBatchSize, hasFetchAnnotation, line
                    ));
                    break;
                }
            }
        }

        return new EntityInfo(name, fieldCount, relations, clazz, source);
    }

    /**
     * Count fields that are persisted (non-transient, non-static, non-relation).
     * This gives us a proxy for "how heavy is this entity to load".
     */
    private int countPersistentFields(ClassOrInterfaceDeclaration clazz) {
        int count = 0;
        for (FieldDeclaration field : clazz.getFields()) {
            if (field.isStatic()) continue;
            if (field.getAnnotationByName("Transient").isPresent()) continue;
            count++;
        }
        return count;
    }

    private String resolveTargetType(FieldDeclaration field) {
        Type type = field.getElementType();
        if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType cit = (ClassOrInterfaceType) type;
            // For collections (List<X>, Set<X>), extract the generic arg
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                Type arg = cit.getTypeArguments().get().get(0);
                return arg.asString();
            }
            return cit.getNameAsString();
        }
        return type.asString();
    }

    private String extractFetchType(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) ann).getPairs()) {
                if ("fetch".equals(pair.getNameAsString())) {
                    String value = pair.getValue().toString();
                    if (value.contains("LAZY")) return "LAZY";
                    if (value.contains("EAGER")) return "EAGER";
                }
            }
        }
        return null;
    }

    // ── Inner classes ──────────────────────────────────────────────────────────

    public static class EntityInfo {
        private final String name;
        private final int fieldCount;
        private final List<RelationInfo> relations;
        private final ClassOrInterfaceDeclaration declaration;
        private final ParsedSource source;

        public EntityInfo(String name, int fieldCount, List<RelationInfo> relations,
                          ClassOrInterfaceDeclaration declaration, ParsedSource source) {
            this.name = name;
            this.fieldCount = fieldCount;
            this.relations = relations;
            this.declaration = declaration;
            this.source = source;
        }

        public String getName() { return name; }
        public int getFieldCount() { return fieldCount; }
        public List<RelationInfo> getRelations() { return relations; }
        public ClassOrInterfaceDeclaration getDeclaration() { return declaration; }
        public ParsedSource getSource() { return source; }

        /** Count of EAGER relations on this entity (direct, not transitive). */
        public long getEagerRelationCount() {
            return relations.stream().filter(RelationInfo::isEffectivelyEager).count();
        }

        /** True if this looks like a simple lookup/status entity. */
        public boolean isLeafEntity() {
            return fieldCount <= 5 && getEagerRelationCount() == 0;
        }
    }

    public static class RelationInfo {
        private final String fieldName;
        private final String annotationType; // ManyToOne, OneToOne, etc.
        private final String targetTypeName;
        private final String explicitFetchType; // null = JPA default
        private final boolean collection;
        private final boolean hasBatchSize;
        private final boolean hasFetchAnnotation;
        private final int line;
        private EntityInfo resolvedTarget; // populated in pass 2

        public RelationInfo(String fieldName, String annotationType, String targetTypeName,
                            String explicitFetchType, boolean collection,
                            boolean hasBatchSize, boolean hasFetchAnnotation, int line) {
            this.fieldName = fieldName;
            this.annotationType = annotationType;
            this.targetTypeName = targetTypeName;
            this.explicitFetchType = explicitFetchType;
            this.collection = collection;
            this.hasBatchSize = hasBatchSize;
            this.hasFetchAnnotation = hasFetchAnnotation;
            this.line = line;
        }

        public String getFieldName() { return fieldName; }
        public String getAnnotationType() { return annotationType; }
        public String getTargetTypeName() { return targetTypeName; }
        public String getExplicitFetchType() { return explicitFetchType; }
        public boolean isCollection() { return collection; }
        public boolean hasBatchSize() { return hasBatchSize; }
        public boolean hasFetchAnnotation() { return hasFetchAnnotation; }
        public int getLine() { return line; }
        public EntityInfo getResolvedTarget() { return resolvedTarget; }

        void setResolvedTarget(EntityInfo target) { this.resolvedTarget = target; }

        /** True if this relation loads eagerly (explicitly or by JPA default). */
        public boolean isEffectivelyEager() {
            if ("LAZY".equals(explicitFetchType)) return false;
            if ("EAGER".equals(explicitFetchType)) return true;
            // JPA defaults: ManyToOne/OneToOne = EAGER, OneToMany/ManyToMany = LAZY
            return !collection;
        }

        public boolean isImplicitEager() {
            return explicitFetchType == null && !collection;
        }
    }

    public static class CyclePath {
        public final EntityInfo entity;
        public final RelationInfo relation;

        public CyclePath(EntityInfo entity, RelationInfo relation) {
            this.entity = entity;
            this.relation = relation;
        }
    }

    public static class EagerCycle {
        private final List<CyclePath> path;

        public EagerCycle(List<CyclePath> path) {
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
        }

        public List<CyclePath> getPath() { return path; }

        /** e.g. "DeliveryPoint -> DeliveryPointStatus -> DeliveryPoint" */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (CyclePath cp : path) {
                sb.append(cp.entity.getName()).append('.').append(cp.relation.getFieldName())
                        .append(" -> ");
            }
            // Close the cycle
            sb.append(path.get(0).entity.getName());
            return sb.toString();
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.CallSiteSchemaReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.model.ContentExtractor;
import ai.tessary.pipeline.CallSiteFact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validates each output against its call site's captured schema; non-JSON or a violation fires, which is what the
 * calling code's parse would hit. A gen_ai message envelope is unwrapped to the final assistant text. Quiet wherever
 * validation isn't meaningful. Schema validation is a fact, so all firings are HIGH.
 */
public final class MalformedOutputDetector implements BuiltInDetector {

    private static final int MAX_VIOLATIONS_IN_EVIDENCE = 5;

    private final CallSiteSchemaReads schemas;
    private final ObjectMapper mapper;
    // Schema retrieval is blocked outright: the schema is agent-authored from customer repo content, so a remote $ref
    // would be SSRF and a file: $ref would read the host. An unresolvable $ref fails compile() and is treated as none.
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaLoader(
                    loader -> loader.fetchRemoteResources(false).block(iri -> true)));

    public MalformedOutputDetector(CallSiteSchemaReads schemas, ObjectMapper mapper) {
        this.schemas = schemas;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return Kind.MALFORMED_OUTPUT;
    }

    /**
     * A schema arriving or changing invalidates everything already swept; see
     * {@link BuiltInDetector#callSiteFactsRead}.
     */
    @Override
    public Set<CallSiteFact> callSiteFactsRead() {
        return Set.of(CallSiteFact.OUTPUT_SCHEMA);
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        return detectBatch(List.of(obs), config).get(0);
    }

    @Override
    public List<Detection> detectBatch(List<SubstrateObservation> batch, @Nullable String config) {
        List<Detection> out = new ArrayList<>(batch.size());
        Set<String> siteIds = new HashSet<>();
        for (SubstrateObservation o : batch) {
            out.add(Detection.none());
            String callSiteId = o.callSiteId();
            String output = o.output();
            if (callSiteId != null && output != null && !output.isBlank()) {
                siteIds.add(callSiteId);
            }
        }
        if (siteIds.isEmpty()) return out;

        Map<String, String> schemaJsonBySite =
                schemas.callSiteOutputSchemas(batch.get(0).projectId(), siteIds);
        if (schemaJsonBySite.isEmpty()) return out;
        // Optional-valued so a compile failure is cached too; computeIfAbsent would retry a null per observation.
        Map<String, Optional<Schema>> compiled = new HashMap<>();

        for (int i = 0; i < batch.size(); i++) {
            SubstrateObservation o = batch.get(i);
            String siteId = o.callSiteId();
            String out2 = o.output();
            String schemaJson = siteId == null ? null : schemaJsonBySite.get(siteId);
            if (schemaJson == null || out2 == null || out2.isBlank()) continue;
            Optional<Schema> schema = compiled.computeIfAbsent(siteId, id -> Optional.ofNullable(compile(schemaJson)));
            if (schema.isEmpty()) continue; // uncompilable stored schema: treated as none declared
            out.set(i, validate(schema.get(), out2));
        }
        return out;
    }

    private Detection validate(Schema schema, String output) {
        JsonNode node;
        try {
            node = mapper.readTree(output);
        } catch (JsonProcessingException e) {
            return Detection.fired(Detection.Severity.WARN, notJsonEvidence());
        }
        String assistantText = assistantTextFromMessageEnvelope(node);
        if (assistantText != null) {
            // No assistant text means no completion to validate.
            if (assistantText.isBlank()) return Detection.none();
            try {
                node = mapper.readTree(assistantText);
            } catch (JsonProcessingException e) {
                return Detection.fired(Detection.Severity.WARN, notJsonEvidence());
            }
        }
        List<Error> violations;
        try {
            // networknt reads through Jackson 3; the Jackson 2 node crosses over as its JSON text, so both libraries
            // judge the same document.
            violations = schema.validate(node.toString(), InputFormat.JSON);
        } catch (RuntimeException e) {
            // Treated as none declared rather than failing the sweep on one poisoned schema.
            return Detection.none();
        }
        if (violations.isEmpty()) return Detection.none();
        List<Violation> ordered = violations.stream()
                .map(MalformedOutputDetector::toViolation)
                .sorted(Comparator.comparing(Violation::field).thenComparing(Violation::message))
                .limit(MAX_VIOLATIONS_IN_EVIDENCE)
                .toList();
        return Detection.fired(Detection.Severity.WARN, violationEvidence(ordered));
    }

    /** {@code field} collapses array indices so violations on different items count against one declared field. */
    private record Violation(String path, String field, String keyword, String message) {}

    /**
     * {@code required}'s instance location is the parent object; the missing name comes from
     * {@link Error#getProperty()}.
     */
    private static Violation toViolation(Error vm) {
        String field = collapsedPath(vm.getInstanceLocation());
        String keyword = vm.getKeyword();
        String property = vm.getProperty();
        if ("required".equals(keyword) && property != null && !property.isBlank()) {
            field = field.isEmpty() ? property : field + "." + property;
        }
        String path = field.isEmpty() ? "$" : "$." + field;
        return new Violation(path, field, keyword, vm.getMessage());
    }

    private static String collapsedPath(NodePath location) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < location.getNameCount(); i++) {
            Object element = location.getElement(i);
            if (element instanceof Integer) {
                sb.append("[]");
            } else {
                if (sb.length() > 0) sb.append('.');
                sb.append(element);
            }
        }
        return sb.toString();
    }

    /**
     * Null for a bare payload (validate as-is), {@code ""} for an envelope with no assistant text. Only a role-tagged
     * array counts as an envelope. Public so {@code MalformedOutputDetailService} shows the document this detector
     * actually judged.
     */
    public static @Nullable String assistantTextFromMessageEnvelope(JsonNode node) {
        if (!ContentExtractor.isMessageEnvelope(node)) return null;
        for (int i = node.size() - 1; i >= 0; i--) {
            JsonNode msg = node.get(i);
            if (msg.isObject() && "assistant".equals(msg.path("role").asText(""))) {
                return ContentExtractor.messageContentText(msg);
            }
        }
        return "";
    }

    private @Nullable Schema compile(String schemaJson) {
        try {
            Schema schema = schemaRegistry.getSchema(schemaJson);
            // $ref resolution is lazy in networknt; force it so a bad ref fails at compile instead of on every
            // validate().
            schema.initializeValidators();
            return schema;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String notJsonEvidence() {
        return "{\"reason\":\"not_json\"}";
    }

    private String violationEvidence(List<Violation> violations) {
        ObjectNode root = mapper.createObjectNode();
        root.put("reason", "schema_violation");
        ArrayNode arr = root.putArray("violations");
        for (Violation v : violations) {
            ObjectNode n = arr.addObject();
            n.put("path", v.path());
            n.put("field", v.field());
            n.put("keyword", v.keyword());
            n.put("message", v.message());
        }
        return root.toString();
    }
}

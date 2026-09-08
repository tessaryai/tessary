// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.substrate.CallSiteSchemaReads;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import ai.tessary.evals.model.ContentExtractor;
import ai.tessary.evals.pipeline.CallSiteFact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.DisallowSchemaLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Malformed Output built-in: a deterministic structured-output check. A call site whose code
 * declares a structured output has that JSON Schema captured during agentic synthesis
 * ({@code call_site.output_schema}); this detector validates each observation's output against its
 * call site's schema. An output that isn't JSON at all, or that violates the schema, fires — that
 * is exactly the failure the calling code's parse would hit in production.
 *
 * <p>On the native gen_ai path {@code observation.output} carries the {@code gen_ai.output.messages}
 * envelope (a role-tagged messages array), not the bare completion the schema describes — the
 * envelope is detected and the final assistant message's text content is what gets validated
 * (see {@link #assistantTextFromMessageEnvelope}). A bare payload is validated as-is.
 *
 * <p>Quiet by construction everywhere validation isn't meaningful: observations with no call site,
 * call sites with no captured schema, blank outputs, and stored schemas that don't compile (including
 * schemas whose {@code $ref}s can't be resolved) are all skipped. All firings are HIGH confidence —
 * schema validation is a fact, not a judgment. {@link #detectBatch} loads the batch's schemas in one
 * read and compiles each distinct schema once per batch, caching compile failures too.
 */
public final class MalformedOutputDetector implements BuiltInDetector {

    private static final int MAX_VIOLATIONS_IN_EVIDENCE = 5;

    private final CallSiteSchemaReads schemas;
    private final ObjectMapper mapper;
    // 2020-12 is the dialect default; a schema declaring its own $schema is honored by the factory.
    // Remote schema retrieval is disallowed: the stored schema is agent-authored from customer repo
    // content, so a remote $ref would be an SSRF vector (networknt's default loader chain fetches
    // http(s) IRIs). Standard-dialect meta-schemas are built in and never need a fetch; a schema
    // whose $ref can't be resolved without the network simply fails compile() and is treated as none.
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaLoaders(loaders -> loaders.add(DisallowSchemaLoader.getInstance())));

    public MalformedOutputDetector(CallSiteSchemaReads schemas, ObjectMapper mapper) {
        this.schemas = schemas;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return Kind.MALFORMED_OUTPUT;
    }

    /**
     * Gated on the captured schema: with none, every observation of that call site is skipped. A
     * schema arriving (or changing) therefore invalidates everything already swept — see {@link
     * BuiltInDetector#callSiteFactsRead}.
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
        // Optional-valued so a compile failure is cached too: one compile attempt per distinct
        // schema per batch (computeIfAbsent alone would retry a null result per observation).
        Map<String, Optional<JsonSchema>> compiled = new HashMap<>();

        for (int i = 0; i < batch.size(); i++) {
            SubstrateObservation o = batch.get(i);
            String siteId = o.callSiteId();
            String out2 = o.output();
            String schemaJson = siteId == null ? null : schemaJsonBySite.get(siteId);
            if (schemaJson == null || out2 == null || out2.isBlank()) continue;
            Optional<JsonSchema> schema =
                    compiled.computeIfAbsent(siteId, id -> Optional.ofNullable(compile(schemaJson)));
            if (schema.isEmpty()) continue; // uncompilable stored schema: treated as none declared
            out.set(i, validate(schema.get(), out2));
        }
        return out;
    }

    /**
     * Validate one output against the call site's schema: not-JSON and schema violations fire. When
     * the output is a gen_ai message envelope, the final assistant message's text content is the
     * document validated — that is the completion the calling code's parse sees.
     */
    private Detection validate(JsonSchema schema, String output) {
        JsonNode node;
        try {
            node = mapper.readTree(output);
        } catch (JsonProcessingException e) {
            return Detection.fired(Detection.Severity.WARN, evidence("not_json", List.of()));
        }
        String assistantText = assistantTextFromMessageEnvelope(node);
        if (assistantText != null) {
            // An envelope with no assistant text carries no completion to validate — quiet, like
            // a blank bare output.
            if (assistantText.isBlank()) return Detection.none();
            try {
                node = mapper.readTree(assistantText);
            } catch (JsonProcessingException e) {
                return Detection.fired(Detection.Severity.WARN, evidence("not_json", List.of()));
            }
        }
        Set<ValidationMessage> violations;
        try {
            violations = schema.validate(node);
        } catch (RuntimeException e) {
            // Schema pathology surfacing at validate despite preload: same discipline as compile() —
            // treated as none declared, never failing the whole sweep on one poisoned schema.
            return Detection.none();
        }
        if (violations.isEmpty()) return Detection.none();
        List<String> messages = violations.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .limit(MAX_VIOLATIONS_IN_EVIDENCE)
                .toList();
        return Detection.fired(Detection.Severity.WARN, evidence("schema_violation", messages));
    }

    /**
     * The final assistant message's text content when {@code node} is a gen_ai message envelope —
     * the shape {@code observation.output} carries on the native gen_ai OTLP path:
     * {@code [{"role":"assistant","parts":[{"type":"text","content":"…"}],"finish_reason":"stop"}]}
     * (role-tagged messages with {@code parts[]} or {@code content}). Returns {@code null} when the
     * node is not a message envelope, i.e. a bare payload to validate as-is; returns {@code ""} for
     * an envelope with no assistant text (nothing to validate). Envelope detection mirrors
     * {@link ContentExtractor}'s shape-aware navigation, and text flattening is delegated to
     * {@link ContentExtractor#flattenContentText} so the detector and the judge agree on how a
     * message's content collapses to text. Only a role-tagged <em>array</em> counts as an envelope —
     * a bare object payload that happens to have a {@code role} property is validated verbatim.
     */
    private static @Nullable String assistantTextFromMessageEnvelope(JsonNode node) {
        if (!ContentExtractor.isMessageEnvelope(node)) return null;
        for (int i = node.size() - 1; i >= 0; i--) {
            JsonNode msg = node.get(i);
            if (msg.isObject() && "assistant".equals(msg.path("role").asText(""))) {
                return ContentExtractor.messageContentText(msg);
            }
        }
        return "";
    }

    private @Nullable JsonSchema compile(String schemaJson) {
        try {
            JsonSchema schema = schemaFactory.getSchema(schemaJson);
            // $ref resolution is lazy in networknt: force it here so an unresolvable (or disallowed
            // remote) ref fails at compile — cached as none-declared — instead of throwing out of
            // validate() once per observation.
            schema.preloadJsonSchema();
            return schema;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String evidence(String reason, List<String> violations) {
        try {
            return violations.isEmpty()
                    ? mapper.writeValueAsString(Map.of("reason", reason))
                    : mapper.writeValueAsString(Map.of("reason", reason, "violations", violations));
        } catch (JsonProcessingException e) {
            return "{\"reason\":\"" + reason + "\"}";
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.substrate.CallSiteSchemaReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.SchemaLoader;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the Malformed Output built-in: schema violations and non-JSON outputs fire,
 * conforming outputs stay quiet, and everything without a meaningful validation target (no call
 * site, no captured schema, blank output) is skipped — including skipping the schema read entirely
 * when nothing in the batch is validatable.
 */
class MalformedOutputDetectorTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"},"
            + "\"confidence\":{\"type\":\"number\"}},\"required\":[\"answer\"]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Set<String>> lookups = new ArrayList<>();

    private MalformedOutputDetector detector(Map<String, String> schemasBySite) {
        CallSiteSchemaReads reads = (projectId, ids) -> {
            lookups.add(ids);
            return schemasBySite.entrySet().stream()
                    .filter(e -> ids.contains(e.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        };
        return new MalformedOutputDetector(reads, mapper);
    }

    private static SubstrateObservation obs(@Nullable String callSiteId, @Nullable String output) {
        return new SubstrateObservation(
                "obs-1",
                "p",
                "t",
                "s",
                null,
                callSiteId,
                "llm",
                "chat",
                null,
                output,
                null,
                "2026-01-01T00:00:00Z",
                null);
    }

    @Test
    void schemaViolationAndNonJsonFire_conformingStaysQuiet() {
        MalformedOutputDetector d = detector(Map.of("cs-1", SCHEMA));
        List<Detection> ds = d.detectBatch(
                List.of(
                        obs("cs-1", "{\"answer\":\"yes\",\"confidence\":0.9}"),
                        obs("cs-1", "{\"confidence\":\"high\"}"),
                        obs("cs-1", "Sure! Here's the answer you asked for.")),
                null);
        assertFalse(ds.get(0).fired(), "a conforming output stays quiet");
        assertTrue(ds.get(1).fired(), "a schema-violating output fires");
        assertEquals(Detection.Severity.WARN, ds.get(1).severity());
        assertEquals(Detection.Confidence.HIGH, ds.get(1).confidence(), "validation is a fact — always HIGH");
        String evidence = Objects.requireNonNull(ds.get(1).evidenceJson());
        assertTrue(evidence.contains("schema_violation"));
        assertTrue(evidence.contains("answer"), "the violation names the missing required field");
        assertTrue(ds.get(2).fired(), "prose where JSON was declared fires");
        assertTrue(Objects.requireNonNull(ds.get(2).evidenceJson()).contains("not_json"));
    }

    @Test
    void observationsWithoutValidationTargetAreSkipped() {
        MalformedOutputDetector d = detector(Map.of("cs-1", SCHEMA));
        List<Detection> ds = d.detectBatch(
                List.of(
                        obs(null, "not json at all"), // no call site
                        obs("cs-other", "not json at all"), // call site without a captured schema
                        obs("cs-1", null), // no output
                        obs("cs-1", "  ")),
                null);
        assertTrue(ds.stream().noneMatch(Detection::fired));
    }

    @Test
    void oneSchemaReadPerBatch() {
        MalformedOutputDetector d = detector(Map.of("cs-1", SCHEMA));
        d.detectBatch(List.of(obs("cs-1", "{}"), obs("cs-1", "{}"), obs("cs-2", "{}")), null);
        assertEquals(1, lookups.size(), "the batch's schemas load in one read");
        assertEquals(Set.of("cs-1", "cs-2"), lookups.get(0));
    }

    @Test
    void uncompilableStoredSchemaIsTreatedAsNoneDeclared() {
        MalformedOutputDetector d = detector(Map.of("cs-1", "{not valid json at all"));
        List<Detection> ds = d.detectBatch(List.of(obs("cs-1", "{\"answer\":42}")), null);
        assertFalse(ds.get(0).fired(), "an unreadable stored schema validates nothing");
    }

    /** The gen_ai.output.messages envelope observation.output carries on the native gen_ai path. */
    private String envelope(String assistantText) {
        try {
            return mapper.writeValueAsString(List.of(Map.of(
                    "role",
                    "assistant",
                    "parts",
                    List.of(Map.of("type", "text", "content", assistantText)),
                    "finish_reason",
                    "stop")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void messageEnvelopeIsUnwrapped_assistantPayloadIsWhatGetsValidated() {
        MalformedOutputDetector d = detector(Map.of("cs-1", SCHEMA));
        List<Detection> ds = d.detectBatch(
                List.of(
                        obs("cs-1", envelope("{\"answer\":\"yes\",\"confidence\":0.9}")),
                        obs("cs-1", envelope("{\"confidence\":\"high\"}")),
                        obs("cs-1", envelope("Sure! Here's the answer you asked for."))),
                null);
        assertFalse(ds.get(0).fired(), "an envelope-wrapped conforming payload stays quiet");
        assertTrue(ds.get(1).fired(), "an envelope-wrapped schema-violating payload fires");
        assertTrue(Objects.requireNonNull(ds.get(1).evidenceJson()).contains("schema_violation"));
        assertTrue(
                Objects.requireNonNull(ds.get(1).evidenceJson()).contains("answer"),
                "the violation is about the unwrapped payload, not the envelope");
        assertTrue(ds.get(2).fired(), "envelope-wrapped prose where JSON was declared fires");
        assertTrue(Objects.requireNonNull(ds.get(2).evidenceJson()).contains("not_json"));
    }

    @Test
    void envelopeWithoutAssistantMessageStaysQuiet() {
        MalformedOutputDetector d = detector(Map.of("cs-1", SCHEMA));
        List<Detection> ds = d.detectBatch(
                List.of(obs("cs-1", "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"hi\"}]}]")), null);
        assertFalse(ds.get(0).fired(), "an envelope with no assistant completion has nothing to validate");
    }

    @Test
    void bareJsonArrayOfObjectsWithoutRolesIsNotTreatedAsEnvelope() {
        // A legit bare payload that is itself an array must keep validating verbatim.
        String arraySchema = "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"answer\"]}}";
        MalformedOutputDetector d = detector(Map.of("cs-1", arraySchema));
        List<Detection> ds = d.detectBatch(
                List.of(obs("cs-1", "[{\"answer\":\"yes\"}]"), obs("cs-1", "[{\"confidence\":1}]")), null);
        assertFalse(ds.get(0).fired(), "a conforming bare array payload stays quiet");
        assertTrue(ds.get(1).fired(), "a violating bare array payload fires");
    }

    /**
     * SSRF guard: an agent-authored schema whose $ref points anywhere outside itself (IMDS, a local
     * port, the host's files) must never be fetched. A live local server serves a schema the output
     * violates, so a fetch would both show up as a hit and make the detector fire. The control half
     * proves the harness can see a fetch: networknt's remote fetcher, pointed at the same server, hits it.
     */
    @Test
    void refSchemasAreNeverFetched_treatedAsNoneDeclared(@TempDir Path dir) throws Exception {
        String served = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}";
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = served.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/schema.json";
            Path file = Files.writeString(dir.resolve("schema.json"), served);
            MalformedOutputDetector d = detector(Map.of(
                    "cs-http", "{\"$ref\":\"" + url + "\"}",
                    "cs-file", "{\"$ref\":\"" + file.toUri() + "\"}"));
            List<Detection> ds =
                    d.detectBatch(List.of(obs("cs-http", "{\"answer\":42}"), obs("cs-file", "{\"answer\":42}")), null);

            assertFalse(ds.get(0).fired(), "an http $ref schema is uncompilable, never fetched");
            assertFalse(ds.get(1).fired(), "a file: $ref schema is uncompilable, never read");
            assertEquals(0, hits.get(), "the detector made no request");

            SchemaRegistry fetching = SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12, b -> b.schemaLoader(SchemaLoader.getRemoteFetcher()));
            fetching.getSchema("{\"$ref\":\"" + url + "\"}").initializeValidators();
            assertEquals(1, hits.get(), "control: a fetching registry does reach the server");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refsInsideTheSchemaStillResolve() {
        MalformedOutputDetector d = detector(Map.of(
                "cs-1",
                "{\"$id\":\"https://example.com/answer\",\"$defs\":{\"answer\":{\"type\":\"string\"}},"
                        + "\"type\":\"object\",\"properties\":{\"answer\":{\"$ref\":\"#/$defs/answer\"}}}"));
        List<Detection> ds =
                d.detectBatch(List.of(obs("cs-1", "{\"answer\":42}"), obs("cs-1", "{\"answer\":\"yes\"}")), null);
        assertTrue(ds.get(0).fired(), "a $ref into the schema's own $defs is enforced");
        assertFalse(ds.get(1).fired());
    }

    @Test
    void violationFieldCollapsesArrayIndexes() {
        MalformedOutputDetector d = detector(Map.of(
                "cs-1",
                "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\",\"items\":"
                        + "{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}},"
                        + "\"required\":[\"qty\"]}}}}"));
        Detection fired = d.detect(obs("cs-1", "{\"items\":[{\"sku\":\"a\",\"qty\":1},{\"sku\":2}]}"), null);
        String evidence = Objects.requireNonNull(fired.evidenceJson());
        assertTrue(evidence.contains("\"field\":\"items[].sku\""), evidence);
        assertTrue(evidence.contains("\"path\":\"$.items[].sku\""), evidence);
        assertTrue(evidence.contains("\"field\":\"items[].qty\""), "required names the missing property: " + evidence);
    }

    /**
     * The per-observation entry point judges exactly as the batch does: a violating output fires with the same
     * evidence, and a conforming one stays quiet, whichever path the caller took.
     */
    @Test
    void detectJudgesOneObservationAsTheBatchWould() {
        MalformedOutputDetector d = detector(Map.of("cs-1", SCHEMA));
        SubstrateObservation violating = obs("cs-1", "{\"confidence\":\"high\"}");

        assertEquals(d.detectBatch(List.of(violating), null).get(0), d.detect(violating, null));
        assertTrue(d.detect(violating, null).fired());
        assertFalse(d.detect(obs("cs-1", "{\"answer\":\"yes\"}"), null).fired());
    }
}

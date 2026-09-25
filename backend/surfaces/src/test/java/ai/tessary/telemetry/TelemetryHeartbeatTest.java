// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.edition.Edition;
import ai.tessary.pricing.PriceBook;
import ai.tessary.pricing.PriceBookFetcher;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.UsageUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * The heartbeat against home.tessary.ai's own contract. No network and no Spring context.
 *
 * <p>{@code telemetry/home-ping.v1.schema.json} is a verbatim copy of {@code contracts/ping.v1.schema.json}
 * from the tessary-home repository, which home generates from the zod schema its {@code POST /v1/ping}
 * handler validates with. Validating the real payload against it is what catches this client drifting
 * from the server: a renamed field, a missing required one, or a format home would answer 400 to. When
 * home changes the contract, replace the copy with the new file and let this test say what broke.
 */
class TelemetryHeartbeatTest {

    private static final String INSTANCE_ID = "3f1c2a9e-7b4d-4e8a-9c21-5d6e7f8a9b0c";

    private final ObjectMapper mapper = new ObjectMapper();

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final CaseRepository cases = mock(CaseRepository.class);
    private final MetricRollupRepository rollups = mock(MetricRollupRepository.class);
    private final PriceBookRepository priceBooks = mock(PriceBookRepository.class);
    private final PriceBookFetcher fetcher = mock(PriceBookFetcher.class);

    private static final String HELD_DIGEST = "a5ad23f7a2d98249588a2f21a305f8e3741bba450fd6216bde505cb9c4bee98a";

    private static JsonSchema homePingSchema() throws IOException {
        try (InputStream in = TelemetryHeartbeatTest.class.getResourceAsStream("/telemetry/home-ping.v1.schema.json")) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
        }
    }

    private TelemetryHeartbeat heartbeat(
            TelemetryProperties props, InstanceIdRepository instanceIds, HomeTessaryClient client) {
        return new TelemetryHeartbeat(
                props,
                instanceIds,
                client,
                projects,
                findings,
                cases,
                rollups,
                priceBooks,
                fetcher,
                mapper,
                Edition.open());
    }

    /**
     * The one gate this whole subsystem hangs off: {@link TelemetryProperties#isEnabled()} must be checked
     * BEFORE anything else in {@link TelemetryHeartbeat#tick()} runs — no DB read (not even the harmless
     * {@link InstanceIdRepository}, which itself writes on first call), no HTTP client touch. This is what
     * makes devdocs/reference/telemetry-contract.md §3's "zero outbound calls, including DNS resolution,
     * when disabled" true.
     */
    @Test
    void disabledTelemetryTouchesNothingDownstream() {
        TelemetryProperties props = new TelemetryProperties();
        props.setEnabled(false);
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        HomeTessaryClient client = mock(HomeTessaryClient.class);

        heartbeat(props, instanceIds, client).tick();

        verifyNoInteractions(instanceIds, client, projects, findings, cases, rollups, priceBooks, fetcher);
    }

    @Test
    void postsAPayloadHomesSchemaAcceptsToV1Ping() throws Exception {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        when(instanceIds.nextPingSeq()).thenReturn(7L);
        when(projects.countAll()).thenReturn(3L);
        when(rollups.installLifetimeTotal(UsageUnit.INGESTED_SPANS)).thenReturn(1_204_551L);
        when(findings.countAll()).thenReturn(88L);
        when(cases.countAll()).thenReturn(12L);
        when(rollups.installLifetimeTotal(UsageUnit.L1_EVALS)).thenReturn(40_210L);
        when(priceBooks.currentDigest(PriceBook.SOURCE_LITELLM)).thenReturn(HELD_DIGEST);
        HomeTessaryClient client = mock(HomeTessaryClient.class);
        when(client.postJson(anyString(), anyString())).thenReturn(204);

        heartbeat(new TelemetryProperties(), instanceIds, client).tick();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).postJson(eq("/v1/ping"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());

        Set<ValidationMessage> violations = homePingSchema().validate(sent);
        assertTrue(violations.isEmpty(), "home would answer 400: " + violations);
        assertEquals(1, sent.get("contract_version").asInt());
        assertEquals(INSTANCE_ID, sent.get("instance_id").asText());
        assertEquals(7L, sent.get("ping_seq").asLong());
        assertEquals("open", sent.get("edition").asText());
        JsonNode counts = sent.get("counts");
        assertEquals(3L, counts.get("projects").asLong());
        assertEquals(1_204_551L, counts.get("spans").asLong());
        assertEquals(88L, counts.get("findings").asLong());
        assertEquals(12L, counts.get("cases").asLong());
        assertEquals(40_210L, counts.get("l1").asLong());
        assertEquals(HELD_DIGEST, sent.path("price_book").path("digest").asText());
        assertEquals(
                PriceBookFetcher.SUPPORTED_SCHEMA,
                sent.path("price_book").path("schema_max").asInt());
    }

    @Test
    void checksThePriceBookAfterPinging() throws Exception {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        HomeTessaryClient client = mock(HomeTessaryClient.class);

        heartbeat(new TelemetryProperties(), instanceIds, client).tick();

        InOrder order = inOrder(client, fetcher);
        order.verify(client).postJson(eq("/v1/ping"), anyString());
        order.verify(fetcher).refresh();
    }

    @Test
    void aFailedPingStillChecksThePriceBook() throws Exception {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenThrow(new IllegalStateException("database unavailable"));

        heartbeat(new TelemetryProperties(), instanceIds, mock(HomeTessaryClient.class))
                .tick();

        verify(fetcher).refresh();
    }

    @Test
    void aFailedPriceBookCheckNeverEscapesTheTick() {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        when(fetcher.refresh()).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> heartbeat(new TelemetryProperties(), instanceIds, mock(HomeTessaryClient.class))
                .tick());
    }

    @Test
    void anInstallHoldingNoDigestOmitsItButStillSaysWhatItCanParse() throws Exception {
        ObjectNode payload = heartbeat(
                        new TelemetryProperties(), mock(InstanceIdRepository.class), mock(HomeTessaryClient.class))
                .payload(INSTANCE_ID, 0, Instant.parse("2026-09-13T07:20:00Z"), null, null);

        assertFalse(payload.path("price_book").has("digest"));
        assertEquals(
                PriceBookFetcher.SUPPORTED_SCHEMA,
                payload.path("price_book").path("schema_max").asInt());
        assertTrue(homePingSchema().validate(payload).isEmpty());
    }

    @Test
    void homesSchemaDeclaresEveryCountThisClientSends() throws Exception {
        // additionalProperties is true, so the schema would accept an undeclared count and home would silently
        // strip it. Pin the declared set to what counts() sends.
        JsonNode declared;
        try (InputStream in = TelemetryHeartbeatTest.class.getResourceAsStream("/telemetry/home-ping.v1.schema.json")) {
            declared = mapper.readTree(in).path("properties").path("counts").path("properties");
        }
        assertEquals(
                Set.of("projects", "spans", "findings", "cases", "l1"), Set.copyOf(fieldNames((ObjectNode) declared)));
    }

    @Test
    void aFailedCountDropsOnlyTheCounts() throws Exception {
        // A partial counts object would read as zero for whatever was missing, so it goes out whole or not at all.
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        when(projects.countAll()).thenReturn(3L);
        when(findings.countAll()).thenThrow(new IllegalStateException("statement timeout"));
        HomeTessaryClient client = mock(HomeTessaryClient.class);
        when(client.postJson(anyString(), anyString())).thenReturn(204);

        heartbeat(new TelemetryProperties(), instanceIds, client).tick();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).postJson(eq("/v1/ping"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertFalse(sent.has("counts"));
        assertTrue(homePingSchema().validate(sent).isEmpty());
        assertNull(heartbeat(new TelemetryProperties(), instanceIds, client).counts());
    }

    @Test
    void readsTheInstanceIdBeforeTakingAPingSeq() throws Exception {
        // nextPingSeq updates the singleton row, which only exists once get() has minted it.
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        HomeTessaryClient client = mock(HomeTessaryClient.class);

        heartbeat(new TelemetryProperties(), instanceIds, client).tick();

        InOrder order = inOrder(instanceIds);
        order.verify(instanceIds).get();
        order.verify(instanceIds).nextPingSeq();
    }

    @Test
    void sendsOnlyTheFieldsTheContractNames() {
        ObjectNode payload = heartbeat(
                        new TelemetryProperties(), mock(InstanceIdRepository.class), mock(HomeTessaryClient.class))
                .payload(
                        INSTANCE_ID,
                        0,
                        Instant.parse("2026-09-13T07:20:00.123456Z"),
                        mapper.createObjectNode(),
                        HELD_DIGEST);

        assertEquals(
                Set.of(
                        "contract_version",
                        "instance_id",
                        "ping_seq",
                        "sent_at",
                        "app_version",
                        "edition",
                        "os",
                        "arch",
                        "counts",
                        "price_book"),
                Set.copyOf(fieldNames(payload)));
        assertEquals("2026-09-13T07:20:00.123456Z", payload.get("sent_at").asText());
        // The retired bucket fields never go out (contract §1).
        assertFalse(payload.has("org_count_bucket"));
        assertFalse(payload.has("timestamp"));
    }

    @Test
    void theFirstPingIsSequenceZeroAndStillValid() throws Exception {
        ObjectNode payload = heartbeat(
                        new TelemetryProperties(), mock(InstanceIdRepository.class), mock(HomeTessaryClient.class))
                .payload(INSTANCE_ID, 0, Instant.parse("2026-09-13T07:20:00Z"), null, null);

        assertTrue(homePingSchema().validate(payload).isEmpty());
    }

    @Test
    void theSchemaCopyRejectsWhatHomeRejects() throws Exception {
        // Proves the validator above can fail, so a green run means something: the ping this client sent
        // before /v1 (no ping_seq, `timestamp` instead of `sent_at`) is exactly what home answers 400 to.
        ObjectNode old = mapper.createObjectNode();
        old.put("contract_version", 1);
        old.put("instance_id", INSTANCE_ID);
        old.put("app_version", "dev");
        old.put("timestamp", "2026-09-13T07:20:00Z");

        Set<ValidationMessage> violations = homePingSchema().validate(old);

        assertTrue(violations.toString().contains("ping_seq"), violations.toString());
        assertTrue(violations.toString().contains("sent_at"), violations.toString());
    }

    @Test
    void aFailedSendNeverEscapesTheTick() throws Exception {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        HomeTessaryClient client = mock(HomeTessaryClient.class);
        when(client.postJson(anyString(), anyString())).thenThrow(new IOException("connection refused"));

        assertDoesNotThrow(
                () -> heartbeat(new TelemetryProperties(), instanceIds, client).tick());
    }

    @Test
    void aRepositoryFailureNeverEscapesTheTick() {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenThrow(new IllegalStateException("database unavailable"));
        HomeTessaryClient client = mock(HomeTessaryClient.class);

        assertDoesNotThrow(
                () -> heartbeat(new TelemetryProperties(), instanceIds, client).tick());
        verifyNoInteractions(client);
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /**
     * A send interrupted mid-flight (the scheduler shutting down) must leave the thread's interrupt flag set:
     * swallowing it would let the shutdown hang on a thread that no longer knows it was asked to stop. The
     * price-book check still runs, as for any other failed send.
     */
    @Test
    void anInterruptedSendKeepsTheInterruptFlagAndStillChecksThePriceBook() throws Exception {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        HomeTessaryClient client = mock(HomeTessaryClient.class);
        when(client.postJson(anyString(), anyString())).thenThrow(new InterruptedException("shutdown"));

        heartbeat(new TelemetryProperties(), instanceIds, client).tick();

        assertTrue(Thread.interrupted(), "the interrupt must survive the tick (and is cleared here)");
        verify(fetcher).refresh();
    }

    /**
     * A failed read of the held price book's digest drops only the digest: the ping still goes out, still says
     * which manifest schema this install parses, and still validates against home's contract.
     */
    @Test
    void anUnreadableHeldDigestIsOmittedAndThePingStillGoesOut() throws Exception {
        InstanceIdRepository instanceIds = mock(InstanceIdRepository.class);
        when(instanceIds.get()).thenReturn(INSTANCE_ID);
        when(priceBooks.currentDigest(PriceBook.SOURCE_LITELLM)).thenThrow(new IllegalStateException("db down"));
        HomeTessaryClient client = mock(HomeTessaryClient.class);
        when(client.postJson(anyString(), anyString())).thenReturn(204);

        heartbeat(new TelemetryProperties(), instanceIds, client).tick();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).postJson(eq("/v1/ping"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertFalse(sent.path("price_book").has("digest"));
        assertEquals(
                PriceBookFetcher.SUPPORTED_SCHEMA,
                sent.path("price_book").path("schema_max").asInt());
        assertTrue(homePingSchema().validate(sent).isEmpty());
    }

    /**
     * The host OS goes out as its bare family, never the versioned {@code os.name} ("Windows 11" carries a
     * version home has no field for). A host outside the three families is sent under its own lower-cased
     * name, and an empty name as {@code unknown} rather than an empty string.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "Windows 11, windows",
        "Mac OS X, macos",
        "Darwin, macos",
        "Linux, linux",
        "FreeBSD, freebsd",
        "'', unknown",
    })
    void theHostOsIsReportedAsItsFamily(String osName, String family) {
        assertEquals(family, TelemetryHeartbeat.osFamily(osName));
    }
}

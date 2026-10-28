// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.PROJECT_ID;
import static ai.tessary.mcp.McpToolHarness.errorText;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.structured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedSentenceText;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.BehaviorDtos.EvidenceExportPage;
import ai.tessary.classifier.finding.BehaviorDtos.EvidenceExportRow;
import ai.tessary.classifier.finding.BehaviorDtos.EvidenceSpanView;
import ai.tessary.classifier.finding.BehaviorDtos.FindingEvidencePage;
import ai.tessary.classifier.finding.BehaviorDtos.FindingEvidenceSpanPage;
import ai.tessary.classifier.finding.ClassifierDetail;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EarlierMessage;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The finding MCP tools ({@code list_findings}, {@code get_finding}, {@code get_finding_evidence}) at the wrapper,
 * with {@link FindingService} mocked. The service is the project scope and the per-detector gate: a finding whose
 * detector the org lacks reads not-found.
 */
class McpFindingToolsTest {

    private FindingService behaviorDrift;
    private McpToolHarness mcp;

    @BeforeEach
    void setup() {
        this.behaviorDrift = mock(FindingService.class);
        this.mcp = registryWith().findings(behaviorDrift).build();
    }

    private static FindingRow row(
            String id,
            String classifierKey,
            String causeKey,
            String subjectKind,
            String subjectId,
            @Nullable String subjectLabel,
            @Nullable String callSiteId,
            long sampleCount,
            String payloadJson) {
        return new FindingRow(
                id,
                PROJECT_ID,
                classifierKey,
                causeKey,
                subjectKind,
                subjectId,
                subjectLabel,
                callSiteId,
                FindingRow.Status.OPEN,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                /* title */ null,
                /* basis */ null,
                /* severity */ null,
                sampleCount,
                payloadJson,
                /* evidenceCountsJson */ null,
                /* sinceVersionId */ null,
                /* escalatedAt */ null,
                /* triageVerdict */ null,
                /* triageAction */ null,
                /* triageSummary */ null,
                /* triageCitationsJson */ null,
                /* triagedAt */ null,
                /* humanVerdictAt */ null,
                null,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z");
    }

    private static BehaviorFindingDetailView sampleFinding() {
        FindingRow row = row(
                "find-1",
                BuiltInDetector.Kind.DURATION_DRIFT,
                "cause-key-1",
                FindingRow.SubjectKind.CLASSIFIER,
                "clf-1",
                "cause-key-1",
                "cs-1",
                5L,
                // The native vocabulary the scoped cause key folds in, exactly as the writer merges it.
                "{\"cause_kind\":\"" + FindingRow.Cause.MALFORMED_RATE + "\",\"workflow_key\":\"workflow-1\","
                        + "\"native_cause_key\":\"cause-key-1\"}");
        // No evidence on the detail: get_finding returns the claim and get_finding_evidence pages the population.
        return BehaviorFindingDetailView.of(row, null, null, null, null, null, null, null);
    }

    /** The same finding after triage ruled, which must never reach an agent. */
    private static BehaviorFindingDetailView triagedFinding() {
        BehaviorFindingDetailView base = sampleFinding();
        BehaviorFindingView f = base.finding();
        return new BehaviorFindingDetailView(
                new BehaviorFindingView(
                        f.id(),
                        f.callSiteId(),
                        f.causeKind(),
                        f.causeKey(),
                        f.title(),
                        f.detector(),
                        f.workflowKey(),
                        f.firstSeenAt(),
                        f.lastSeenAt(),
                        f.traceCount(),
                        f.status(),
                        "positive",
                        "opened_case",
                        "the claim is sound",
                        List.of(),
                        "2026-06-03T00:00:00Z",
                        f.triageStatus(),
                        f.triageProgress(),
                        new BehaviorFindingView.TriageRunView(
                                "run-1",
                                1,
                                "2026-06-03T00:00:00Z",
                                "2026-06-03T00:04:00Z",
                                12,
                                "Reading flagged answers 51 to 58",
                                "Read flagged answers 1 to 50 of 58",
                                4_000L,
                                "Let me read the rest",
                                5_000L,
                                null),
                        f.humanVerdictAt(),
                        f.caseId()),
                base.metric(),
                base.toolError(),
                base.malformedOutput(),
                base.secretLeak(),
                base.armedWindow(),
                base.frustration(),
                base.groundedness(),
                base.classifierDetail());
    }

    /**
     * The context firewall, pinned where prompt tests cannot reach: RCA runs with a project key against this surface,
     * so {@code get_finding} once handed it the triage ruling the lane must reach independently. {@code
     * AgenticRcaPromptTest} greps the prompt and could not see a leak in the tool.
     */
    @Test
    void getFinding_neverReturnsTheTriageRuling() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-1")).thenReturn(triagedFinding());

        JsonNode finding =
                structured(mcp.callTool("get_finding", "{\"id\":\"find-1\"}")).get("finding");

        assertTrue(finding.get("triageVerdict").isNull(), "triageVerdict reached an agent");
        assertTrue(finding.get("triageSummary").isNull(), "triageSummary reached an agent");
        assertTrue(finding.get("triageAction").isNull(), "triageAction reached an agent");
        assertTrue(finding.get("triagedAt").isNull(), "triagedAt reached an agent");
        // A person's ruling uses the same columns, so humanVerdictAt would still tell RCA that a person decided.
        assertTrue(finding.get("humanVerdictAt").isNull(), "humanVerdictAt reached an agent");
        assertEquals(0, finding.get("triageCitations").size(), "triage citations reached an agent");
        // The run's step words say what triage read, which is triage leaking into RCA.
        assertTrue(finding.get("triageRun").isNull(), "the triage run reached an agent");
        assertFalse(
                finding.toString().contains("the claim is sound"),
                "the triage summary's text is still somewhere on the wire");
    }

    private static ClassifierDetail classifierDetail(List<String> failingTraces) {
        return new ClassifierDetail(
                new ToolErrorEvidence.RateDetail(
                        "cs-1",
                        0.02,
                        0.09,
                        7.0,
                        400,
                        120,
                        11,
                        List.of(),
                        false,
                        failingTraces,
                        "2026-06-01T00:00:00Z",
                        null,
                        null,
                        "up",
                        6.2,
                        4.0,
                        0.3,
                        0.5),
                8L,
                "flagged",
                0.7,
                500L,
                4.0,
                null,
                new ClassifierDetail.Words("reply", "replies", "since onset", "learned rate", null, "above 0.7"));
    }

    /** {@link #triagedFinding()} with an owner's detail on it. */
    private static BehaviorFindingDetailView triagedFindingWith(ClassifierDetail detail) {
        BehaviorFindingDetailView t = triagedFinding();
        return new BehaviorFindingDetailView(
                t.finding(),
                t.metric(),
                t.toolError(),
                t.malformedOutput(),
                t.secretLeak(),
                t.armedWindow(),
                t.frustration(),
                t.groundedness(),
                detail);
    }

    /** An owner's rate loses its trace ids at the agent door, as every built-in rate does. */
    @Test
    void getFinding_classifierDetailDropsFailingTraces() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-1"))
                .thenReturn(triagedFindingWith(classifierDetail(List.of("trace-secret-9"))));

        JsonNode body = structured(mcp.callTool("get_finding", "{\"id\":\"find-1\"}"));

        assertEquals(
                0, body.get("classifierDetail").get("rate").get("failingTraces").size());
        assertFalse(body.toString().contains("trace-secret-9"), "a failing trace id reached the agent");
    }

    @Test
    void getFinding_readsByIdProjectScoped() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-1")).thenReturn(sampleFinding());

        JsonNode structured = structured(mcp.callTool("get_finding", "{\"id\":\"find-1\"}"));

        assertEquals("find-1", structured.get("finding").get("id").asText());
        assertEquals("cs-1", structured.get("finding").get("callSiteId").asText());
        assertEquals(
                FindingRow.Cause.MALFORMED_RATE,
                structured.get("finding").get("causeKind").asText());
        verify(behaviorDrift).finding(PROJECT_ID, "find-1");
    }

    @Test
    void getFinding_notFoundIsCleanToolError() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "nope"))
                .thenThrow(new TessaryException(ClassifierError.FINDING_NOT_FOUND, "nope"));
        String text = errorText(mcp.callTool("get_finding", "{\"id\":\"nope\"}"));
        assertTrue(text.contains("nope"), text);
    }

    private static BehaviorFindingDetailView toolErrorFinding() {
        FindingRow row = row(
                "find-2",
                "tool_error",
                "tool_error:tool:search_docs:up",
                FindingRow.SubjectKind.TOOL,
                "tool:search_docs",
                "search_docs",
                "cs-1",
                80L,
                "{\"cause_kind\":\"" + FindingRow.Cause.RATE_SHIFT + "\",\"measure\":\"tool_error_rate\","
                        + "\"bucket\":{\"kind\":\"tool\",\"key\":\"tool:search_docs\"},"
                        + "\"direction\":\"up\",\"statistic\":12.5,\"threshold\":6.0,\"criticality\":40.2,"
                        + "\"effect_size\":0.31,\"delta_pp\":8.4,\"counts_basis\":\"onset\","
                        + "\"rate\":{\"ref\":0.02,\"cur\":0.1},\"n_ref\":500,\"n_cur\":80,"
                        + "\"failures\":{\"cur\":8},"
                        + "\"failing_traces\":[\"trace-secret-1\",\"trace-secret-2\"],"
                        + "\"onset_at\":\"2026-06-01T00:00:00Z\",\"window\":{\"kind\":\"recomputed\"}}");
        return BehaviorFindingDetailView.of(row, null, null, null, null, null, null, null);
    }

    /** The summary carries every R3 number, and the agent view still drops {@code failing_traces}. */
    @Test
    void getFinding_toolError_carriesSummaryNumbers_andDropsFailingTraces() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-2")).thenReturn(toolErrorFinding());

        JsonNode body = structured(mcp.callTool("get_finding", "{\"id\":\"find-2\"}"));
        JsonNode toolError = body.get("toolError");

        assertEquals("up", toolError.get("direction").asText());
        assertEquals(12.5, toolError.get("statistic").asDouble(), 0.001);
        assertEquals(6.0, toolError.get("threshold").asDouble(), 0.001);
        assertEquals(0.31, toolError.get("effectSize").asDouble(), 0.001);
        assertEquals(40.2, toolError.get("criticality").asDouble(), 0.001);
        assertEquals(0, toolError.get("failingTraces").size(), "the agent view carries no trace ids");
        assertFalse(body.toString().contains("trace-secret-1"), "a failing trace id reached the agent");
    }

    private static BehaviorFindingDetailView malformedOutputFinding() {
        FindingRow row = row(
                "find-4",
                BuiltInDetector.Kind.MALFORMED_OUTPUT,
                "malformed_output_rate:cs-3",
                FindingRow.SubjectKind.TOOL,
                "cs-3",
                "cs-3",
                "cs-3",
                12L,
                "{\"cause_kind\":\"" + FindingRow.Cause.MALFORMED_RATE + "\"}");
        MalformedOutputEvidence.MalformedDetail malformed = new MalformedOutputEvidence.MalformedDetail(
                new ToolErrorEvidence.RateDetail(
                        "cs-3",
                        0.01,
                        0.2,
                        19.0,
                        500,
                        60,
                        12,
                        List.of(),
                        false,
                        List.of("trace-malformed-1"),
                        "2026-06-01T00:00:00Z",
                        null,
                        null,
                        "up",
                        9.0,
                        5.0,
                        0.4,
                        30.0),
                List.of(),
                2L,
                1L);
        return BehaviorFindingDetailView.of(row, malformed, null, null, null, null, null, null);
    }

    @Test
    void getFinding_malformedOutput_dropsFailingTraces() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-4")).thenReturn(malformedOutputFinding());

        JsonNode body = structured(mcp.callTool("get_finding", "{\"id\":\"find-4\"}"));
        JsonNode rate = body.get("malformedOutput").get("rate");

        assertEquals("up", rate.get("direction").asText());
        assertEquals(0, rate.get("failingTraces").size(), "the agent view carries no trace ids");
        assertFalse(body.toString().contains("trace-malformed-1"), "a failing trace id reached the agent");
    }

    private static BehaviorFindingDetailView secretLeakFinding() {
        FindingRow row = row(
                "find-3",
                BuiltInDetector.Kind.SECRET_LEAK,
                "secret_leak:aws-access-token",
                FindingRow.SubjectKind.CLASSIFIER,
                "classifier-1",
                "aws-access-token",
                "cs-2",
                3L,
                "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\",\"native_cause_key\":\"aws-access-token\"}");
        SecretLeakEvidence.SecretLeakDetail secretLeak = new SecretLeakEvidence.SecretLeakDetail(
                "aws-access-token",
                FindingRow.Confidence.HIGH,
                3L,
                2L,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                List.of(new SecretLeakEvidence.SecretLeakKeyView("AKIA…WXYZ", 3L, 2L, "2026-06-02T00:00:00Z", false)),
                List.of(new SecretLeakEvidence.SecretLeakLeakView(
                        "2026-06-02T00:00:00Z", "AKIA…WXYZ", "masked", "trace-secret-9", "span-1")),
                "event_count",
                1L,
                86_400L,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z");
        return BehaviorFindingDetailView.of(row, null, secretLeak, null, null, null, null, null);
    }

    /**
     * The R3 checklist for Secret Leak: {@code basis}, {@code threshold} and {@code windowSeconds} present, the
     * masked key kept, witness ids dropped (decision 12).
     */
    @Test
    void getFinding_secretLeak_carriesSummaryNumbers_andDropsWitnessIds() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-3")).thenReturn(secretLeakFinding());

        JsonNode body = structured(mcp.callTool("get_finding", "{\"id\":\"find-3\"}"));
        JsonNode secretLeak = body.get("secretLeak");

        assertEquals("event_count", secretLeak.get("basis").asText());
        assertEquals(1, secretLeak.get("threshold").asLong());
        assertEquals(86_400, secretLeak.get("windowSeconds").asLong());
        JsonNode leak = secretLeak.get("leaks").get(0);
        assertTrue(leak.get("traceId").isNull(), "the agent view carries no trace id");
        assertTrue(leak.get("spanId").isNull(), "the agent view carries no span id");
        assertEquals("AKIA…WXYZ", leak.get("masked").asText(), "the masked key still reaches the agent");
        assertFalse(body.toString().contains("trace-secret-9"), "a witness trace id reached the agent");
        assertTrue(body.get("armedWindow").isNull(), "secret leak's richer block replaces the generic one");
    }

    private static BehaviorFindingDetailView armedWindowFinding() {
        FindingRow row = row(
                "find-5",
                BuiltInDetector.Kind.FRUSTRATION,
                "per_span_classifier:classifier-2",
                FindingRow.SubjectKind.CLASSIFIER,
                "classifier-2",
                "Frustration",
                null,
                7L,
                "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\",\"basis\":\"event_count\","
                        + "\"observed\":7,\"threshold\":5,\"window_seconds\":86400,"
                        + "\"window_start\":\"2026-06-01T00:00:00Z\",\"window_end\":\"2026-06-02T00:00:00Z\"}");
        return BehaviorFindingDetailView.of(row, null, null, null, null, null, null, null);
    }

    /**
     * The armed-window summary for classifiers with no richer block, built from what {@code ClassifierArming} wrote.
     */
    @Test
    void getFinding_armedWindow_carriesSummaryForAClassifierWithNoRicherDetailOfItsOwn() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-5")).thenReturn(armedWindowFinding());

        JsonNode body = structured(mcp.callTool("get_finding", "{\"id\":\"find-5\"}"));
        JsonNode armedWindow = body.get("armedWindow");

        assertEquals("event_count", armedWindow.get("basis").asText());
        assertEquals(7, armedWindow.get("observed").asLong());
        assertEquals(5, armedWindow.get("threshold").asLong());
        assertEquals(86_400, armedWindow.get("windowSeconds").asLong());
    }

    @Test
    void listFindings_passesEveryFilterThroughAndWidensOnlyForExplicitAll() throws Exception {
        when(behaviorDrift.findings(eq(PROJECT_ID), any(), any(), any(), anyBoolean()))
                .thenReturn(new BehaviorFindingsView(List.of(), "repo"));

        structured(mcp.callTool(
                "list_findings",
                "{\"status\":\"open\",\"call_site_id\":\"cs-1\",\"detector\":\"duration_drift\","
                        + "\"include\":\"all\"}"));

        verify(behaviorDrift).findings(PROJECT_ID, "open", "cs-1", "duration_drift", false);
    }

    /** Every page names the Layer-2 lane its findings are ruled on. */
    @Test
    void listFindings_rendersLane() throws Exception {
        when(behaviorDrift.findings(eq(PROJECT_ID), any(), any(), any(), anyBoolean()))
                .thenReturn(new BehaviorFindingsView(List.of(), "repo"));

        JsonNode body = structured(mcp.callTool("list_findings", "{}"));

        assertEquals("repo", body.get("lane").asText());
    }

    @Test
    void listFindings_serviceFailureIsACleanToolError() throws Exception {
        when(behaviorDrift.findings(eq(PROJECT_ID), any(), any(), any(), anyBoolean()))
                .thenThrow(new TessaryException(ClassifierError.FINDING_NOT_FOUND, "boom"));

        String text = errorText(mcp.callTool("list_findings", "{}"));

        assertTrue(text.contains("boom"), text);
    }

    private static Map<String, Long> counts(long member, long baseline) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String role : FindingEvidenceRow.Role.ALL) out.put(role, 0L);
        out.put(FindingEvidenceRow.Role.MEMBER, member);
        out.put(FindingEvidenceRow.Role.BASELINE, baseline);
        return out;
    }

    private static FindingEvidenceSpanPage samplePage(@Nullable String nextCursor) {
        return new FindingEvidenceSpanPage(
                List.of(
                        new EvidenceSpanView(
                                FindingEvidenceRow.Role.MEMBER,
                                0,
                                "sess-4",
                                "trace-7",
                                null,
                                "plan",
                                "llm",
                                "ok",
                                null,
                                null,
                                "2026-02-03T10:00:00Z",
                                7293L,
                                4120L,
                                0.031,
                                List.of("claude-opus-5", "claude-haiku-5"),
                                true,
                                true,
                                true,
                                "otto-von-bismarck",
                                "v-12",
                                "user-3",
                                4000L,
                                100L,
                                10L,
                                5L,
                                5L,
                                4,
                                9,
                                812L,
                                64L,
                                true,
                                Map.of("service.instance.id", "i-1"),
                                "what the span was given",
                                "what it returned",
                                null,
                                null,
                                null),
                        new EvidenceSpanView(
                                FindingEvidenceRow.Role.MEMBER,
                                3,
                                "sess-9",
                                "trace-8",
                                "span-2",
                                "lookup",
                                "tool",
                                "error",
                                "error",
                                "Timeout",
                                "2026-02-03T10:04:00Z",
                                84L,
                                null,
                                null,
                                List.of(),
                                false,
                                false,
                                false,
                                "otto-von-bismarck",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                Map.of(),
                                "tool input",
                                "tool output",
                                null,
                                null,
                                null)),
                nextCursor,
                counts(120, 0),
                counts(120, 0));
    }

    /**
     * A row carries its measurements, not just a pointer: ids round-trip (a trace-grain row has no span id), {@code
     * rank} keeps its gaps as the detector's order, and latency, tokens, cost and call site ride along so a reader
     * can rank before opening anything.
     */
    @Test
    void getFindingEvidence_pagesRowsWithTheirMeasurements() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(PROJECT_ID, "find-1", null, 100, null))
                .thenReturn(samplePage("cursor-2"));

        JsonNode body = structured(mcp.callTool("get_finding_evidence", "{\"finding_id\":\"find-1\"}"));

        assertEquals(2, body.get("rows").size());
        JsonNode first = body.get("rows").get(0);
        assertEquals("trace-7", first.get("traceId").asText());
        assertTrue(first.get("spanId").isNull(), "a trace-grain row carries no span id");
        assertEquals(7293, first.get("latencyMs").asLong());
        assertEquals(4120, first.get("totalTokens").asLong());
        List<String> models = new java.util.ArrayList<>();
        first.get("models").forEach(m -> models.add(m.asText()));
        assertEquals(List.of("claude-opus-5", "claude-haiku-5"), models);
        assertTrue(first.get("notRolledUp").asBoolean(), "a whole-run row can flag its trace as not rolled up yet");
        assertTrue(first.get("partialCost").asBoolean(), "a whole-run row can flag unpriced spans");
        assertTrue(first.get("staleTotals").asBoolean(), "a whole-run row can flag an unsettled rollup");
        assertEquals("otto-von-bismarck", first.get("callSiteId").asText());
        assertEquals(4000, first.get("inputTokens").asLong());
        assertEquals(5, first.get("reasoningTokens").asLong());
        assertEquals(4, first.get("traceIndex").asInt());
        assertEquals(9, first.get("sessionTraces").asInt());
        assertEquals("user-3", first.get("userId").asText());
        assertEquals("v-12", first.get("projectVersionId").asText());
        assertEquals(812, first.get("inputBytes").asLong());
        assertTrue(first.get("payloadAvailable").asBoolean(), first.toString());
        assertEquals("i-1", first.get("attributes").get("service.instance.id").asText());
        JsonNode second = body.get("rows").get(1);
        assertEquals(0, second.get("attributes").size(), "an empty short list is {} on the wire, not absent");
        assertEquals("trace-8", second.get("traceId").asText());
        assertEquals("span-2", second.get("spanId").asText());
        assertEquals(3, second.get("rank").asInt());
        assertEquals(84, second.get("latencyMs").asLong());
        assertEquals("Timeout", second.get("errorType").asText());
        // A tool span has neither; an empty cell is honest, a zero would not be.
        assertTrue(second.get("totalTokens").isNull(), "a tool span has no tokens");
        assertEquals(0, second.get("models").size(), "a tool span carries no model");
        assertFalse(second.get("notRolledUp").asBoolean(), "the flags are always false on a single-step row");
        assertEquals("cursor-2", body.get("nextCursor").asText());
        assertEquals(120, body.get("counts").get("member").asLong());
    }

    /** Previews never reach this door; an agent asks {@code get_span} for a body on purpose. */
    @Test
    void getFindingEvidence_dropsThePayloadPreviews() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(PROJECT_ID, "find-1", null, 100, null))
                .thenReturn(samplePage(null));

        JsonNode body = structured(mcp.callTool("get_finding_evidence", "{\"finding_id\":\"find-1\"}"));

        JsonNode first = body.get("rows").get(0);
        assertFalse(first.has("inputPreview"), first.toString());
        assertFalse(first.has("outputPreview"), first.toString());
    }

    @Test
    void getFindingEvidence_passesRoleLimitAndCursorThroughAndClampsTheLimit() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(eq(PROJECT_ID), eq("find-1"), any(), anyInt(), any()))
                .thenReturn(samplePage(null));

        structured(mcp.callTool(
                "get_finding_evidence",
                "{\"finding_id\":\"find-1\",\"role\":\"member\",\"limit\":9000,\"cursor\":\"c-1\"}"));

        verify(behaviorDrift).findingEvidenceSpans(PROJECT_ID, "find-1", FindingEvidenceRow.Role.MEMBER, 1000, "c-1");
    }

    /** {@code rowsOmitted} separates "I did not ask for rows" from "there are none". */
    @Test
    void getFindingEvidence_countOnlyReturnsBothCountMapsAndNoRows() throws Exception {
        when(behaviorDrift.findingEvidence(PROJECT_ID, "find-1"))
                .thenReturn(new FindingEvidencePage(List.of(), null, true, counts(118, 0), counts(120, 0)));

        JsonNode body =
                structured(mcp.callTool("get_finding_evidence", "{\"finding_id\":\"find-1\",\"count_only\":true}"));

        assertEquals(0, body.get("refs").size());
        assertTrue(body.get("rowsOmitted").asBoolean(), "count_only omits rows rather than returning none");
        verify(behaviorDrift, org.mockito.Mockito.never()).findingEvidenceSpans(any(), any(), any(), anyInt(), any());
        // Live below recorded is retention, not a lost write.
        assertEquals(118, body.get("counts").get("member").asLong());
        assertEquals(120, body.get("recordedCounts").get("member").asLong());
        // A detector with no reference side reports an explicit zero, never an absent key.
        assertTrue(body.get("counts").has("baseline"), "every role is reported: " + body.get("counts"));
        assertEquals(0, body.get("counts").get("baseline").asLong());
    }

    /** An unknown role is an error: an empty page would look like a finished audit of nothing. */
    @Test
    void getFindingEvidence_unknownRoleIsToolError_andNeverCallsService() throws Exception {
        String text =
                errorText(mcp.callTool("get_finding_evidence", "{\"finding_id\":\"find-1\",\"role\":\"members\"}"));

        assertTrue(text.contains("members"), text);
        assertTrue(text.contains(FindingEvidenceRow.Role.BASELINE), text);
        verify(behaviorDrift, org.mockito.Mockito.never()).findingEvidenceSpans(any(), any(), any(), anyInt(), any());
        verify(behaviorDrift, org.mockito.Mockito.never()).findingEvidence(any(), any());
    }

    /** Cross-tenant ids and ungranted detectors both read not-found, as for {@code get_finding}. */
    @Test
    void getFindingEvidence_notFoundIsCleanToolError() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(eq(PROJECT_ID), eq("other-tenant"), any(), anyInt(), any()))
                .thenThrow(new TessaryException(ClassifierError.FINDING_NOT_FOUND, "other-tenant"));

        String text = errorText(mcp.callTool("get_finding_evidence", "{\"finding_id\":\"other-tenant\"}"));

        assertTrue(text.contains("other-tenant"), text);
    }

    private static EvidenceSpanView evidenceRow(String role, String traceId, @Nullable String spanId) {
        return evidenceRow(role, traceId, spanId, Map.of());
    }

    private static EvidenceSpanView evidenceRow(
            String role, String traceId, @Nullable String spanId, Map<String, String> attributes) {
        return new EvidenceSpanView(
                role,
                0,
                "sess-1",
                traceId,
                spanId,
                "answer",
                "llm",
                "ok",
                null,
                null,
                "2026-02-03T10:00:00Z",
                900L,
                300L,
                0.01,
                List.of("claude-haiku-5"),
                false,
                false,
                false,
                "rag-answer",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                attributes,
                "question",
                "answer",
                null,
                null,
                null);
    }

    /**
     * A groundedness witness span row carries the sentences the model flagged, as text with a score, so an agent
     * reads what was flagged without a {@code get_span} per answer. Only the page's witness span refs are asked
     * for: a trace-grain witness row and a member row are not answers.
     */
    @Test
    void getFindingEvidence_groundednessWitnessSpanRowsCarryFlaggedSentences() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(PROJECT_ID, "find-g", null, 100, null))
                .thenReturn(new FindingEvidenceSpanPage(
                        List.of(
                                evidenceRow(FindingEvidenceRow.Role.WITNESS, "trace-1", null),
                                evidenceRow(FindingEvidenceRow.Role.WITNESS, "trace-1", "span-1"),
                                evidenceRow(FindingEvidenceRow.Role.WITNESS, "trace-2", "span-2"),
                                evidenceRow(FindingEvidenceRow.Role.MEMBER, "trace-3", "span-3")),
                        null,
                        counts(1, 0),
                        counts(1, 0)));
        List<SpanRef> witnessSpans = List.of(new SpanRef("trace-1", "span-1"), new SpanRef("trace-2", "span-2"));
        when(behaviorDrift.flaggedSentences(PROJECT_ID, "find-g", witnessSpans))
                .thenReturn(Map.of(
                        new SpanRef("trace-1", "span-1"),
                        List.of(
                                new FlaggedSentenceText("The refund window is 90 days.", 0.91),
                                new FlaggedSentenceText(null, 0.62)),
                        new SpanRef("trace-2", "span-2"),
                        List.of()));

        JsonNode rows = structured(mcp.callTool("get_finding_evidence", "{\"finding_id\":\"find-g\"}"))
                .get("rows");

        verify(behaviorDrift).flaggedSentences(PROJECT_ID, "find-g", witnessSpans);
        assertFalse(rows.get(0).has("flaggedSentences"), "a trace-grain witness row is not an answer");
        JsonNode sentences = rows.get(1).get("flaggedSentences");
        assertEquals(2, sentences.size());
        assertEquals(
                "The refund window is 90 days.", sentences.get(0).get("text").asText());
        assertEquals(0.91, sentences.get(0).get("score").asDouble());
        assertTrue(sentences.get(1).get("text").isNull(), "an aged-out answer keeps the score without the text");
        assertEquals(0.62, sentences.get(1).get("score").asDouble());
        assertEquals(0, rows.get(2).get("flaggedSentences").size(), "a witness answer with no sentence left");
        assertFalse(rows.get(3).has("flaggedSentences"), "a member row is not a flagged answer");
    }

    private static EvidenceExportPage exportPage(EvidenceExportRow... rows) {
        return new EvidenceExportPage(List.of(rows), "c-2", counts(2, 0), counts(2, 0), 4000);
    }

    /**
     * The rows are flat on the wire, one level a script reads: the measured fields beside {@code input}, {@code
     * output}, {@code attributes} and, on a frustration witness row only, {@code priorMessages}. Catches a nested or
     * renamed key breaking every script an agent writes over the saved file.
     */
    @Test
    void exportFindingEvidence_rowsAreFlatAndCarryTextAttributesAndPriorMessages() throws Exception {
        when(behaviorDrift.exportEvidence(PROJECT_ID, "find-1", null, 4000, null))
                .thenReturn(exportPage(
                        new EvidenceExportRow(
                                evidenceRow(
                                        FindingEvidenceRow.Role.WITNESS,
                                        "trace-1",
                                        "span-1",
                                        Map.of("service.name", "api")),
                                "the question",
                                null,
                                Map.of("deploy", "v1"),
                                List.of(new EarlierMessage("user", "a"))),
                        new EvidenceExportRow(
                                evidenceRow(FindingEvidenceRow.Role.MEMBER, "trace-2", "span-2"),
                                "another",
                                "an answer",
                                null,
                                null)));

        Map<String, Object> result = mcp.callTool("export_finding_evidence", "{\"finding_id\":\"find-1\"}");
        JsonNode body = structured(result);

        JsonNode witness = body.get("rows").get(0);
        // The row's own bag replaces the short list; two `attributes` keys on one object would be invalid for most
        // readers.
        assertEquals(1, witness.get("attributes").size(), witness.toString());
        assertEquals(2, occurrences(textOf(result), "\"attributes\""), "one attributes key per row");
        assertEquals("witness", witness.get("role").asText());
        assertEquals("trace-1", witness.get("traceId").asText());
        assertEquals("span-1", witness.get("spanId").asText());
        assertEquals(900, witness.get("latencyMs").asLong());
        assertEquals("rag-answer", witness.get("callSiteId").asText());
        assertEquals("the question", witness.get("input").asText());
        assertTrue(witness.has("output") && witness.get("output").isNull(), witness.toString());
        assertEquals("v1", witness.get("attributes").get("deploy").asText());
        assertEquals("user", witness.get("priorMessages").get(0).get("role").asText());
        assertEquals("a", witness.get("priorMessages").get(0).get("content").asText());
        assertFalse(witness.has("measured"), "the measured fields are flat on the row: " + witness);
        assertFalse(witness.has("inputPreview"), "the preview is superseded by the text: " + witness);
        JsonNode member = body.get("rows").get(1);
        assertEquals("an answer", member.get("output").asText());
        assertFalse(member.has("priorMessages"), "only a frustration witness row has prior messages: " + member);
        assertEquals("c-2", body.get("nextCursor").asText());
        assertEquals(4000, body.get("textChars").asInt());
        assertEquals(2, body.get("counts").get("member").asLong());
        assertEquals(2, body.get("recordedCounts").get("member").asLong());
    }

    /** Catches an unbounded {@code text_chars} turning one row into a whole page. */
    @Test
    void exportFindingEvidence_passesRoleAndCursorThroughAndClampsTextChars() throws Exception {
        when(behaviorDrift.exportEvidence(eq(PROJECT_ID), eq("find-1"), any(), anyInt(), any()))
                .thenReturn(exportPage());

        structured(mcp.callTool(
                "export_finding_evidence",
                "{\"finding_id\":\"find-1\",\"role\":\"member\",\"text_chars\":50000,\"cursor\":\"c-1\"}"));
        structured(mcp.callTool("export_finding_evidence", "{\"finding_id\":\"find-1\",\"text_chars\":1}"));
        structured(mcp.callTool("export_finding_evidence", "{\"finding_id\":\"find-1\"}"));

        verify(behaviorDrift).exportEvidence(PROJECT_ID, "find-1", FindingEvidenceRow.Role.MEMBER, 20000, "c-1");
        verify(behaviorDrift).exportEvidence(PROJECT_ID, "find-1", null, 200, null);
        verify(behaviorDrift).exportEvidence(PROJECT_ID, "find-1", null, 4000, null);
    }

    /** An unknown role is an error: an empty export would read as a finding with no evidence. */
    @Test
    void exportFindingEvidence_unknownRoleIsToolError_andNeverCallsService() throws Exception {
        String text =
                errorText(mcp.callTool("export_finding_evidence", "{\"finding_id\":\"find-1\",\"role\":\"members\"}"));

        assertTrue(text.contains("members"), text);
        assertTrue(text.contains("export_finding_evidence"), text);
        assertTrue(text.contains(FindingEvidenceRow.Role.BASELINE), text);
        verify(behaviorDrift, org.mockito.Mockito.never()).exportEvidence(any(), any(), any(), anyInt(), any());
    }

    /** Cross-tenant ids and ungranted detectors both read not-found, as for {@code get_finding_evidence}. */
    @Test
    void exportFindingEvidence_notFoundIsCleanToolError() throws Exception {
        when(behaviorDrift.exportEvidence(eq(PROJECT_ID), eq("other-tenant"), any(), anyInt(), any()))
                .thenThrow(new TessaryException(ClassifierError.FINDING_NOT_FOUND, "other-tenant"));

        String text = errorText(mcp.callTool("export_finding_evidence", "{\"finding_id\":\"other-tenant\"}"));

        assertTrue(text.contains("other-tenant"), text);
    }

    /** The serialized text block, where a duplicate key would still show; the parsed tree keeps only one. */
    @SuppressWarnings("unchecked")
    private static String textOf(Map<String, Object> result) {
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) java.util.Objects.requireNonNull(result.get("content"));
        return String.valueOf(content.get(0).get("text"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}

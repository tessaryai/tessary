// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.BehaviorDtos.EvidenceSpanView;
import ai.tessary.classifier.finding.BehaviorDtos.FindingEvidencePage;
import ai.tessary.classifier.finding.BehaviorDtos.FindingEvidenceSpanPage;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The finding MCP tools ({@code list_findings}, {@code get_finding}, {@code get_finding_evidence}) at the wrapper,
 * with {@link FindingService} mocked. The service is the project scope and the per-detector gate: a finding whose
 * detector the org lacks reads not-found.
 */
class McpFindingToolsTest {

    private static final String PROJECT_ID = "proj-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private FindingService behaviorDrift;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        this.behaviorDrift = mock(FindingService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, "org-1", "proj", "Proj", null, "2026-08-12T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        // Unused by the finding tools, but the registry needs them.
        PipelineService pipeline = mock(PipelineService.class);
        QueryService query = mock(QueryService.class);
        SpanRepository spans = mock(SpanRepository.class);
        SpanPayloadRepository payloads = mock(SpanPayloadRepository.class);
        TraceV2Repository traces = mock(TraceV2Repository.class);
        var registry = new McpToolRegistry(
                pipeline,
                projects,
                query,
                spans,
                payloads,
                traces,
                mock(SessionReadService.class),
                behaviorDrift,
                mock(CaseService.class));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    private TenantContext ctx() {
        return new TenantContext("user-1", null, "org-1", PROJECT_ID, "member", "tok-1");
    }

    private JsonRpc.Request req(int id, String method, @Nullable JsonNode params) {
        return new JsonRpc.Request("2.0", IntNode.valueOf(id), method, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String name, String argsJson) throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}");
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/call", params), ctx());
        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
        return (Map<String, Object>) Objects.requireNonNull(r.result());
    }

    private JsonNode structured(Map<String, Object> result) {
        assertEquals(Boolean.FALSE, result.get("isError"));
        return mapper.valueToTree(Objects.requireNonNull(result.get("structuredContent")));
    }

    private static String errorText(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("isError"), "expected isError=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        return Objects.requireNonNull(content.get(0).get("text")).toString();
    }

    private static BehaviorFindingDetailView sampleFinding() {
        FindingRow row = new FindingRow(
                "find-1",
                PROJECT_ID,
                BuiltInDetector.Kind.DURATION_DRIFT,
                "cause-key-1",
                FindingRow.SubjectKind.CLASSIFIER,
                "clf-1",
                "cause-key-1",
                "cs-1",
                FindingRow.Status.OPEN,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                /* title */ null,
                /* basis */ null,
                /* severity */ null,
                5L,
                // The native vocabulary the scoped cause key folds in, exactly as the writer merges it.
                "{\"cause_kind\":\"" + FindingRow.Cause.MALFORMED_RATE + "\",\"workflow_key\":\"workflow-1\","
                        + "\"native_cause_key\":\"cause-key-1\"}",
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
        // No evidence on the detail: get_finding returns the claim and get_finding_evidence pages the population.
        return BehaviorFindingDetailView.of(row, null, null, null, null, null);
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
                        f.humanVerdictAt(),
                        f.caseId()),
                base.metric(),
                base.toolError(),
                base.malformedOutput(),
                base.secretLeak(),
                base.armedWindow(),
                base.frustration(),
                base.groundedness());
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
                structured(callTool("get_finding", "{\"id\":\"find-1\"}")).get("finding");

        assertTrue(finding.get("triageVerdict").isNull(), "triageVerdict reached an agent");
        assertTrue(finding.get("triageSummary").isNull(), "triageSummary reached an agent");
        assertTrue(finding.get("triageAction").isNull(), "triageAction reached an agent");
        assertTrue(finding.get("triagedAt").isNull(), "triagedAt reached an agent");
        assertEquals(0, finding.get("triageCitations").size(), "triage citations reached an agent");
        assertFalse(
                finding.toString().contains("the claim is sound"),
                "the triage summary's text is still somewhere on the wire");
    }

    /**
     * A person's ruling uses the same columns as triage's, so leaving {@code humanVerdictAt} would still tell RCA
     * that a person decided.
     */
    @Test
    void getFinding_neverReturnsWhoRuled() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-1")).thenReturn(triagedFinding());

        JsonNode finding =
                structured(callTool("get_finding", "{\"id\":\"find-1\"}")).get("finding");

        assertTrue(finding.get("humanVerdictAt").isNull(), "humanVerdictAt reached an agent");
    }

    @Test
    void listFindings_neverReturnsTheTriageRuling() throws Exception {
        when(behaviorDrift.findings(PROJECT_ID, null, null, null, true))
                .thenReturn(new BehaviorFindingsView(List.of(triagedFinding().finding()), "repo"));

        JsonNode body = structured(callTool("list_findings", "{}"));

        JsonNode first = body.get("findings").get(0);
        assertTrue(first.get("triageVerdict").isNull(), "a list row carried the ruling");
        assertTrue(first.get("triageSummary").isNull(), "a list row carried the summary");
    }

    @Test
    void getFinding_readsByIdProjectScoped() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-1")).thenReturn(sampleFinding());

        JsonNode structured = structured(callTool("get_finding", "{\"id\":\"find-1\"}"));

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
        String text = errorText(callTool("get_finding", "{\"id\":\"nope\"}"));
        assertTrue(text.contains("nope"), text);
    }

    @Test
    void getFinding_missingIdIsToolError_andNeverCallsService() throws Exception {
        String text = errorText(callTool("get_finding", "{}"));
        assertTrue(text.contains("id"), text);
        verify(behaviorDrift, org.mockito.Mockito.never()).finding(any(), any());
    }

    private static BehaviorFindingDetailView toolErrorFinding() {
        FindingRow row = new FindingRow(
                "find-2",
                PROJECT_ID,
                "tool_error",
                "tool_error:tool:search_docs:up",
                FindingRow.SubjectKind.TOOL,
                "tool:search_docs",
                "search_docs",
                "cs-1",
                FindingRow.Status.OPEN,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                null,
                null,
                null,
                80L,
                "{\"cause_kind\":\"" + FindingRow.Cause.RATE_SHIFT + "\",\"measure\":\"tool_error_rate\","
                        + "\"bucket\":{\"kind\":\"tool\",\"key\":\"tool:search_docs\"},"
                        + "\"direction\":\"up\",\"statistic\":12.5,\"threshold\":6.0,\"criticality\":40.2,"
                        + "\"effect_size\":0.31,\"delta_pp\":8.4,\"counts_basis\":\"onset\","
                        + "\"rate\":{\"ref\":0.02,\"cur\":0.1},\"n_ref\":500,\"n_cur\":80,"
                        + "\"failures\":{\"cur\":8},"
                        + "\"failing_traces\":[\"trace-secret-1\",\"trace-secret-2\"],"
                        + "\"onset_at\":\"2026-06-01T00:00:00Z\",\"window\":{\"kind\":\"recomputed\"}}",
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
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z");
        return BehaviorFindingDetailView.of(row, null, null, null, null, null);
    }

    /** The summary carries every R3 number, and the agent view still drops {@code failing_traces}. */
    @Test
    void getFinding_toolError_carriesSummaryNumbers_andDropsFailingTraces() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-2")).thenReturn(toolErrorFinding());

        JsonNode body = structured(callTool("get_finding", "{\"id\":\"find-2\"}"));
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
        FindingRow row = new FindingRow(
                "find-4",
                PROJECT_ID,
                BuiltInDetector.Kind.MALFORMED_OUTPUT,
                "malformed_output_rate:cs-3",
                FindingRow.SubjectKind.TOOL,
                "cs-3",
                "cs-3",
                "cs-3",
                FindingRow.Status.OPEN,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                null,
                null,
                null,
                12L,
                "{\"cause_kind\":\"" + FindingRow.Cause.MALFORMED_RATE + "\"}",
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
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z");
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
        return BehaviorFindingDetailView.of(row, malformed, null, null, null, null);
    }

    @Test
    void getFinding_malformedOutput_dropsFailingTraces() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-4")).thenReturn(malformedOutputFinding());

        JsonNode body = structured(callTool("get_finding", "{\"id\":\"find-4\"}"));
        JsonNode rate = body.get("malformedOutput").get("rate");

        assertEquals("up", rate.get("direction").asText());
        assertEquals(0, rate.get("failingTraces").size(), "the agent view carries no trace ids");
        assertFalse(body.toString().contains("trace-malformed-1"), "a failing trace id reached the agent");
    }

    private static BehaviorFindingDetailView secretLeakFinding() {
        FindingRow row = new FindingRow(
                "find-3",
                PROJECT_ID,
                BuiltInDetector.Kind.SECRET_LEAK,
                "secret_leak:aws-access-token",
                FindingRow.SubjectKind.CLASSIFIER,
                "classifier-1",
                "aws-access-token",
                "cs-2",
                FindingRow.Status.OPEN,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                null,
                null,
                null,
                3L,
                "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\",\"native_cause_key\":\"aws-access-token\"}",
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
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z");
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
        return BehaviorFindingDetailView.of(row, null, secretLeak, null, null, null);
    }

    /**
     * The R3 checklist for Secret Leak: {@code basis}, {@code threshold} and {@code windowSeconds} present, the
     * masked key kept, witness ids dropped (decision 12).
     */
    @Test
    void getFinding_secretLeak_carriesSummaryNumbers_andDropsWitnessIds() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-3")).thenReturn(secretLeakFinding());

        JsonNode body = structured(callTool("get_finding", "{\"id\":\"find-3\"}"));
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
        FindingRow row = new FindingRow(
                "find-5",
                PROJECT_ID,
                BuiltInDetector.Kind.FRUSTRATION,
                "per_span_classifier:classifier-2",
                FindingRow.SubjectKind.CLASSIFIER,
                "classifier-2",
                "Frustration",
                null,
                FindingRow.Status.OPEN,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z",
                null,
                null,
                null,
                7L,
                "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\",\"basis\":\"event_count\","
                        + "\"observed\":7,\"threshold\":5,\"window_seconds\":86400,"
                        + "\"window_start\":\"2026-06-01T00:00:00Z\",\"window_end\":\"2026-06-02T00:00:00Z\"}",
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
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z");
        return BehaviorFindingDetailView.of(row, null, null, null, null, null);
    }

    /**
     * The armed-window summary for classifiers with no richer block, built from what {@code ClassifierArming} wrote.
     */
    @Test
    void getFinding_armedWindow_carriesSummaryForAClassifierWithNoRicherDetailOfItsOwn() throws Exception {
        when(behaviorDrift.finding(PROJECT_ID, "find-5")).thenReturn(armedWindowFinding());

        JsonNode body = structured(callTool("get_finding", "{\"id\":\"find-5\"}"));
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

        structured(callTool(
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

        JsonNode body = structured(callTool("list_findings", "{}"));

        assertEquals("repo", body.get("lane").asText());
    }

    @Test
    void listFindings_serviceFailureIsACleanToolError() throws Exception {
        when(behaviorDrift.findings(eq(PROJECT_ID), any(), any(), any(), anyBoolean()))
                .thenThrow(new TessaryException(ClassifierError.FINDING_NOT_FOUND, "boom"));

        String text = errorText(callTool("list_findings", "{}"));

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

        JsonNode body = structured(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\"}"));

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
        JsonNode second = body.get("rows").get(1);
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

        JsonNode body = structured(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\"}"));

        JsonNode first = body.get("rows").get(0);
        assertFalse(first.has("inputPreview"), first.toString());
        assertFalse(first.has("outputPreview"), first.toString());
    }

    @Test
    void getFindingEvidence_passesRoleLimitAndCursorThroughAndClampsTheLimit() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(eq(PROJECT_ID), eq("find-1"), any(), anyInt(), any()))
                .thenReturn(samplePage(null));

        structured(callTool(
                "get_finding_evidence",
                "{\"finding_id\":\"find-1\",\"role\":\"member\",\"limit\":9000,\"cursor\":\"c-1\"}"));

        verify(behaviorDrift).findingEvidenceSpans(PROJECT_ID, "find-1", FindingEvidenceRow.Role.MEMBER, 1000, "c-1");
    }

    /** {@code rowsOmitted} separates "I did not ask for rows" from "there are none". */
    @Test
    void getFindingEvidence_countOnlyReturnsBothCountMapsAndNoRows() throws Exception {
        when(behaviorDrift.findingEvidence(PROJECT_ID, "find-1"))
                .thenReturn(new FindingEvidencePage(List.of(), null, true, counts(118, 0), counts(120, 0)));

        JsonNode body = structured(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\",\"count_only\":true}"));

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
        String text = errorText(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\",\"role\":\"members\"}"));

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

        String text = errorText(callTool("get_finding_evidence", "{\"finding_id\":\"other-tenant\"}"));

        assertTrue(text.contains("other-tenant"), text);
    }
}

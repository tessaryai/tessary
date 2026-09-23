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
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.CapabilityService.CapabilitySet;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-level behaviour of the three classifier-finding MCP tools: {@code list_findings},
 * {@code get_finding} and {@code get_finding_evidence}. All three delegate to
 * {@link FindingService}, which is both the project scope and the per-detector capability gate (a
 * finding whose detector this org lacks reads not-found, same as a genuinely missing id), and render its
 * views verbatim. The {@link FindingService} is mocked — this pins the MCP wrapper contract
 * (project scoping, arg mapping, view shaping, error mapping), not the classifier logic itself.
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
        // The finding tool never uses these; the registry needs them to register the other tools.
        PipelineService pipeline = mock(PipelineService.class);
        QueryService query = mock(QueryService.class);
        SpanRepository spans = mock(SpanRepository.class);
        SpanPayloadRepository payloads = mock(SpanPayloadRepository.class);
        TraceV2Repository traces = mock(TraceV2Repository.class);
        // Every capability on: these tests exercise the tool itself, not the gate.
        Map<Capability, Boolean> allOn = new EnumMap<>(Capability.class);
        for (Capability c : Capability.values()) allOn.put(c, true);
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.resolve(any())).thenReturn(new CapabilitySet(allOn));
        when(capabilities.isEnabled(any(), any())).thenReturn(true);
        var registry = new McpToolRegistry(
                pipeline,
                projects,
                query,
                spans,
                payloads,
                traces,
                mock(SessionReadService.class),
                capabilities,
                behaviorDrift,
                mock(CaseService.class));
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    /** A project-scoped MCP-token context, as AuthFilter mints for an {@code tsy_}. */
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
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                "profile-1:" + FindingRow.Cause.NOVELTY + ":cause-key-1:workflow-1",
                FindingRow.SubjectKind.BEHAVIOR_PROFILE,
                "profile-1",
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
                "{\"cause_kind\":\"" + FindingRow.Cause.NOVELTY + "\",\"workflow_key\":\"workflow-1\","
                        + "\"native_cause_key\":\"cause-key-1\",\"exemplar_verdict_id\":\"verdict-1\"}",
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
        // No evidence set on the detail: get_finding returns the CLAIM, and get_finding_evidence pages
        // the population — which is the split this fixture used to blur by carrying one ref inline.
        return BehaviorFindingDetailView.of(row);
    }

    /** The same finding after triage ruled on it, which is what must never reach an agent. */
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
                        f.evidence(),
                        f.status(),
                        "positive",
                        "opened_case",
                        "the claim is sound",
                        List.of(),
                        "2026-06-03T00:00:00Z",
                        f.triageStatus(),
                        f.humanVerdictAt(),
                        f.conformanceKind(),
                        f.caseId()),
                base.metric(),
                base.toolError(),
                base.baseline(),
                base.malformedOutput(),
                base.secretLeak(),
                base.armedWindow(),
                base.frustration(),
                base.groundedness());
    }

    // ---- registration --------------------------------------------------------------------------

    @Test
    void getFindingToolIsListed() throws Exception {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) Objects.requireNonNull(result.get("tools"));
        var names = tools.stream().map(t -> (String) t.get("name")).toList();
        assertTrue(names.contains("get_finding"), names.toString());
    }

    // ---- get_finding -----------------------------------------------------------------------------

    /**
     * The context firewall, pinned where the prompt tests cannot reach.
     *
     * <p>Layer-3 RCA gets a finding id and nothing else: no ruling, no summary, not even the fact that a
     * triage pass happened. It runs with a project admin key against this surface and is handed its own
     * finding's id, so before this was redacted, {@code get_finding} answered the question the whole lane
     * exists to answer independently. {@code AgenticRcaPromptTest} could not catch it: it greps the
     * prompt string, and this leak was in the tool.
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
     * Under the open/closed model a person's ruling is a ruling like any other — set on the same
     * columns triage writes — so redacting the verdict and leaving {@code humanVerdictAt} standing
     * would still tell RCA that a person decided this, only not what they decided.
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
                FindingRow.Cause.NOVELTY,
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
        return BehaviorFindingDetailView.of(row);
    }

    /**
     * {@code get_finding}'s summary blocks now carry every number R3 added, and the agent view still
     * drops the sample this classifier's blob names: {@code failing_traces}.
     */
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
        return BehaviorFindingDetailView.of(row, malformed, null);
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
        return BehaviorFindingDetailView.of(row, null, secretLeak);
    }

    /**
     * The whole R3 checklist for Secret Leak in one test: the summary numbers ({@code basis}, {@code
     * threshold}, {@code windowSeconds}) that were missing, the masked key that must survive, and the
     * witness trace/span ids that must not — the case decision 12 calls out by name.
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
        return BehaviorFindingDetailView.of(row);
    }

    /**
     * Frustration, groundedness and regex/threshold classifiers had no summary block before R3 — this
     * is the new one, built off exactly what {@code ClassifierArming} already wrote to the payload.
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

    // ---- list_findings ---------------------------------------------------------------------------

    /**
     * {@code get_finding} shipped without a list, which made it a dead end: its own argument description named
     * the only way in ("e.g. from a Classifiers findings page URL"), so an agent could not reach a finding
     * without a human reading the UI and pasting an id. The list has been on {@code FindingController} (formerly {@code BehaviorController}) the
     * whole time.
     */
    @Test
    void listFindingsToolIsListed() throws Exception {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) Objects.requireNonNull(result.get("tools"));
        var names = tools.stream().map(t -> (String) t.get("name")).toList();
        assertTrue(names.contains("list_findings"), names.toString());
    }

    @Test
    void listFindings_defaultsToConfirmedOnly() throws Exception {
        when(behaviorDrift.findings(eq(PROJECT_ID), any(), any(), any(), anyBoolean()))
                .thenReturn(new BehaviorFindingsView(List.of(), "repo"));

        structured(callTool("list_findings", "{}"));

        // confirmedOnly=true with no argument: the default must be the alert list, not the raw lead stream.
        verify(behaviorDrift).findings(PROJECT_ID, null, null, null, true);
    }

    @Test
    void listFindings_passesEveryFilterThroughAndWidensOnlyForExplicitAll() throws Exception {
        when(behaviorDrift.findings(eq(PROJECT_ID), any(), any(), any(), anyBoolean()))
                .thenReturn(new BehaviorFindingsView(List.of(), "repo"));

        structured(callTool(
                "list_findings",
                "{\"status\":\"open\",\"call_site_id\":\"cs-1\",\"detector\":\"behavior_drift\","
                        + "\"include\":\"all\"}"));

        verify(behaviorDrift).findings(PROJECT_ID, "open", "cs-1", "behavior_drift", false);
    }

    /** The lane is rendered on every page: which Layer-2 lane this project's findings are ruled on. */
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

    // ---- get_finding_evidence --------------------------------------------------------------------

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

    @Test
    void getFindingEvidenceToolIsListed() throws Exception {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) Objects.requireNonNull(result.get("tools"));
        var names = tools.stream().map(t -> (String) t.get("name")).toList();
        assertTrue(names.contains("get_finding_evidence"), names.toString());
    }

    /**
     * A row carries what was measured on it, not just a pointer to it: the ids still round-trip (a
     * trace-grain row carries no span id, a span-grain row carries both, since span identity under
     * substrate v2 is the composite key), and {@code rank} survives with its gaps intact — it is the
     * detector's order, not an index — but latency, tokens, cost and call site ride along, which is what
     * lets a reader rank the page before it opens anything.
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
        // A tool span has neither, and an empty cell is the honest answer rather than a zero.
        assertTrue(second.get("totalTokens").isNull(), "a tool span has no tokens");
        assertEquals(0, second.get("models").size(), "a tool span carries no model");
        assertFalse(second.get("notRolledUp").asBoolean(), "the flags are always false on a single-step row");
        assertEquals("cursor-2", body.get("nextCursor").asText());
        assertEquals(120, body.get("counts").get("member").asLong());
    }

    /**
     * The previews stay on the table the UI renders and never reach this door. An agent that wants a
     * body asks {@code get_span} for one span on purpose, instead of being handed a thousand truncated
     * ones it did not ask for.
     */
    @Test
    void getFindingEvidence_dropsThePayloadPreviews() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(PROJECT_ID, "find-1", null, 100, null))
                .thenReturn(samplePage(null));

        JsonNode body = structured(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\"}"));

        JsonNode first = body.get("rows").get(0);
        assertFalse(first.has("inputPreview"), first.toString());
        assertFalse(first.has("outputPreview"), first.toString());
    }

    /** The paging contract: default limit, the cap, the role filter and the cursor all reach the service. */
    @Test
    void getFindingEvidence_passesRoleLimitAndCursorThroughAndClampsTheLimit() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(eq(PROJECT_ID), eq("find-1"), any(), anyInt(), any()))
                .thenReturn(samplePage(null));

        structured(callTool(
                "get_finding_evidence",
                "{\"finding_id\":\"find-1\",\"role\":\"member\",\"limit\":9000,\"cursor\":\"c-1\"}"));

        verify(behaviorDrift).findingEvidenceSpans(PROJECT_ID, "find-1", FindingEvidenceRow.Role.MEMBER, 1000, "c-1");
    }

    /**
     * {@code count_only} is the cheap first call, and its answer must not read as an empty evidence set:
     * {@code rowsOmitted} is what separates "I did not ask for rows" from "there are none".
     */
    @Test
    void getFindingEvidence_countOnlyReturnsBothCountMapsAndNoRows() throws Exception {
        when(behaviorDrift.findingEvidence(PROJECT_ID, "find-1", null, 100, null, true))
                .thenReturn(new FindingEvidencePage(List.of(), null, true, counts(118, 0), counts(120, 0)));

        JsonNode body = structured(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\",\"count_only\":true}"));

        assertEquals(0, body.get("refs").size());
        assertTrue(body.get("rowsOmitted").asBoolean(), "count_only omits rows rather than returning none");
        // The sizing call returns no rows, so there is nothing to join spans to.
        verify(behaviorDrift, org.mockito.Mockito.never()).findingEvidenceSpans(any(), any(), any(), anyInt(), any());
        // Live below recorded is retention, not a lost write — the pair is the whole point of sending both.
        assertEquals(118, body.get("counts").get("member").asLong());
        assertEquals(120, body.get("recordedCounts").get("member").asLong());
        // A detector with no enumerable reference side reports an explicit zero, never an absent key.
        assertTrue(body.get("counts").has("baseline"), "every role is reported: " + body.get("counts"));
        assertEquals(0, body.get("counts").get("baseline").asLong());
    }

    /**
     * An unknown role is an error rather than an empty page. An empty page here would read as "this claim
     * has nothing behind it", which is the one wrong answer that looks like a finished audit.
     */
    @Test
    void getFindingEvidence_unknownRoleIsToolError_andNeverCallsService() throws Exception {
        String text = errorText(callTool("get_finding_evidence", "{\"finding_id\":\"find-1\",\"role\":\"members\"}"));

        assertTrue(text.contains("members"), text);
        assertTrue(text.contains(FindingEvidenceRow.Role.BASELINE), text);
        verify(behaviorDrift, org.mockito.Mockito.never()).findingEvidenceSpans(any(), any(), any(), anyInt(), any());
        verify(behaviorDrift, org.mockito.Mockito.never())
                .findingEvidence(any(), any(), any(), anyInt(), any(), anyBoolean());
    }

    @Test
    void getFindingEvidence_missingFindingIdIsToolError_andNeverCallsService() throws Exception {
        String text = errorText(callTool("get_finding_evidence", "{}"));

        assertTrue(text.contains("finding_id"), text);
        verify(behaviorDrift, org.mockito.Mockito.never()).findingEvidenceSpans(any(), any(), any(), anyInt(), any());
        verify(behaviorDrift, org.mockito.Mockito.never())
                .findingEvidence(any(), any(), any(), anyInt(), any(), anyBoolean());
    }

    /**
     * The gate is the service's, exactly as for {@code get_finding}: a cross-tenant id and a finding whose
     * detector this org does not hold both surface as not-found rather than as forbidden.
     */
    @Test
    void getFindingEvidence_notFoundIsCleanToolError() throws Exception {
        when(behaviorDrift.findingEvidenceSpans(eq(PROJECT_ID), eq("other-tenant"), any(), anyInt(), any()))
                .thenThrow(new TessaryException(ClassifierError.FINDING_NOT_FOUND, "other-tenant"));

        String text = errorText(callTool("get_finding_evidence", "{\"finding_id\":\"other-tenant\"}"));

        assertTrue(text.contains("other-tenant"), text);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseExemplarView;
import ai.tessary.cases.CaseDtos.CaseRulingView;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A case opened from a finding in the shared {@code finding} table, rendered whole: the Layer-2 ruling
 * that opened it, the exemplar trace the finding recorded, and the absorb affordance.
 *
 * <p>It seeds a {@code TOOL_ERROR} case through the open {@code FindingRepository} and
 * {@code FindingEvidenceRepository} and asserts on {@code CaseService.detail}, stating the
 * shared-table zone contract on its own. It sits beside {@code MetricDriftCaseGateIntegrationTest},
 * the other open-classifier case test.
 */
@SpringBootTest
class ToolErrorCaseRenderingIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("tessary.cases.heartbeat-ms", () -> "3600000");
    }

    /** The citations a repo-grounded ruling rests on, in the stored shape. */
    private static final String CITATIONS = "[{\"path\":\"docs/sop/billing.md#L12\",\"reason\":"
            + "\"the SOP requires the account lookup before any balance is quoted\"}]";

    @Autowired
    CaseService service;

    @Autowired
    CaseRepository cases;

    @Autowired
    FindingRepository behaviorFindings;

    @Autowired
    FindingEvidenceRepository findingEvidence;

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository v2traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, v2traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("the behaviour-table arm is unchanged: ruling, recorded exemplar, absorb offered")
    void aBehaviourFindingCaseRendersAsItAlwaysDid() {
        Project p = project("conf-case-control");
        seedTrace(p, "trace-tool-1");
        String findingId = behaviorFindings
                .recordRecomputedCause(
                        Ids.ulid(),
                        p.id(),
                        "tool:lookup_account",
                        320,
                        BehaviorSubstrateRepository.UNATTRIBUTED,
                        Instant.now().toString(),
                        "{\"failing_traces\":[]}",
                        Instant.parse("2020-01-01T00:00:00Z").toString(),
                        Instant.now().toString())
                .findingId();
        findingEvidence.recordExemplarTrace(
                p.id(), findingId, "trace-tool-1", Instant.now().toString());
        behaviorFindings.recordTriage(
                p.id(),
                findingId,
                FindingRow.TriageVerdict.POSITIVE,
                "The retry path swallows the 500.",
                CITATIONS,
                Instant.now().toString());

        CaseDetailView view = service.detail(
                p.id(),
                openCase(
                        p,
                        new CaseKey(
                                CaseRow.Detector.TOOL_ERROR,
                                CaseRow.SubjectKind.TOOL,
                                "tool:lookup_account",
                                "failure_rate"),
                        findingId));

        CaseRulingView ruling = view.ruling();
        assertNotNull(ruling);
        assertEquals(findingId, ruling.findingId());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, ruling.verdict());
        assertEquals(FindingRow.TriageAction.OPENED_CASE, ruling.action());
        assertEquals(
                List.of("trace-tool-1"),
                view.exemplars().stream().map(CaseExemplarView::traceId).toList());
        assertTrue(view.exemplars().stream().allMatch(e -> FindingEvidenceRow.Role.EXEMPLAR.equals(e.role())));
        assertTrue(view.absorbAvailable(), "a behaviour finding still has a reference an absorb can move");
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    /** A plain tenant. Nothing here reads a capability: a tool-error case is always open. */
    private Project project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project();
    }

    private String openCase(Project p, CaseKey key, String findingId) {
        CaseDetection detection = new CaseDetection(
                key,
                key.subjectId(),
                null,
                findingId,
                "something happened",
                "because the detector said so",
                0.4,
                Instant.parse("2026-07-01T10:00:00Z"),
                0.055,
                0.012,
                0.043);
        return cases.open(p.id(), detection, Instant.now()).orElseThrow().id();
    }

    /**
     * A trace a case can hand a reader, with the id the finding names.
     *
     * <p>The ids here are readable labels rather than hex, on purpose: a producer trace id is free-form
     * text by contract, and these are the exact strings the seeded findings record, so hexifying them
     * would only put a translation table between the fixture and its own assertions.
     */
    private void seedTrace(Project p, String traceId) {
        fx.namedTrace(p.id(), traceId, traceId, Instant.now());
    }
}

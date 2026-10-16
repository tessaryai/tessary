// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FakeFindingAdapter;
import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;
import ai.tessary.classifier.finding.dossier.EvidenceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The RCA dossier for a finding a classifier outside the catalog filed. The bugs are an RCA run on a custom finding
 * with no method, and an open edition that invents one.
 */
@ExtendWith(MockitoExtension.class)
class RcaAnalysisServiceTest {

    private static final String PROJECT = "proj-1";

    private static final RcaJobRow JOB =
            new RcaJobRow("job-1", PROJECT, "f1", FindingRow.SubjectKind.CLASSIFIER, "cls-1", "custom", "u1");

    private static final RcaReportRow REPORT = new RcaReportRow(
            "rep-1",
            "job-1",
            FindingRow.SubjectKind.CLASSIFIER,
            "cls-1",
            "Custom x",
            "cs-1",
            "custom",
            RcaReportRow.ReportKind.METRIC_MOVEMENT,
            "2026-10-01T00:00:00Z",
            "2026-10-01T12:00:00Z",
            "2026-10-02T00:00:00Z",
            0,
            0,
            0,
            "running",
            null,
            null,
            null,
            null,
            null,
            null,
            RcaReportRow.Engine.AGENTIC,
            null,
            "2026-10-02T00:00:00Z",
            null);

    private static final FindingClaim CLAIM = claim("custom-x");

    private static FindingClaim claim(String classifierKey) {
        return claim(classifierKey, "{\"version_id\":\"v1\"}", "{\"witness\":1}");
    }

    private static FindingClaim claim(String classifierKey, String payloadJson, String evidenceCountsJson) {
        return new FindingClaim(
                "f1",
                PROJECT,
                classifierKey,
                "cls-1:cs-1",
                FindingRow.SubjectKind.CLASSIFIER,
                "cls-1",
                "Custom x",
                "cs-1",
                "2026-10-01T00:00:00Z",
                "2026-10-02T00:00:00Z",
                null,
                null,
                null,
                40,
                payloadJson,
                evidenceCountsJson,
                "2026-10-01T00:00:00Z");
    }

    @Mock
    FindingRepository findings;

    @Mock
    FindingEvidenceRepository evidence;

    @Mock
    RcaReportRepository reports;

    @Mock
    AgenticRcaEngine agenticEngine;

    private static final FindingEvidenceRow WITNESS_ROW = new FindingEvidenceRow(
            "ev-1", PROJECT, "f1", "s1", "t1", "sp1", FindingEvidenceRow.Role.WITNESS, null, "2026-10-01T00:00:00Z");

    @BeforeEach
    void stubTheSubject() {
        when(reports.findByJobId(PROJECT, "job-1")).thenReturn(Optional.of(REPORT));
        when(findings.caseSiblingIds(PROJECT, "f1")).thenReturn(List.of());
        when(findings.findClaim(PROJECT, "f1")).thenReturn(Optional.of(CLAIM));
        when(evidence.listByFinding(PROJECT, "f1")).thenReturn(List.of(WITNESS_ROW));
        when(agenticEngine.run(eq(JOB), eq(REPORT), any(), any(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.NO_CAUSE_FOUND, "nothing found", List.of(), List.of(), null, false));
    }

    /**
     * An owned finding's RCA dossier carries its adapter's RCA method, not its triage method, and, with no evidence
     * file of the owner's, the payload fenced longer than any backtick run in it. The bugs are the triage method,
     * written for a ruling, handed to the RCA agent, and a payload holding a code block closing the fence early.
     */
    @Test
    void anOwnedFindingsRcaDossierCarriesItsAdaptersRcaMethod() {
        when(evidence.claimFirst(PROJECT, "f1", ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP))
                .thenReturn(new FindingEvidenceRepository.Head(List.of(WITNESS_ROW), false));
        when(findings.findClaim(PROJECT, "f1"))
                .thenReturn(Optional.of(claim("custom-x", "{\"note\":\"```sh rm ```\"}", "{\"witness\":1}")));

        Map<String, String> dossier = dossierOf(FindingAdapters.of(
                List.of(FakeFindingAdapter.owning("custom-x", "# Triage method\n", "# RCA method\n", null))));

        assertEquals(
                Set.of("findings/1-f1/finding.md", "findings/1-f1/evidence.md", "method.md", "tools.md"),
                dossier.keySet());
        assertEquals("# RCA method\n", dossier.get("method.md"));
        assertEquals(
                "# Classifier evidence\n\n````json\n{\"note\":\"```sh rm ```\"}\n````\n",
                dossier.get("findings/1-f1/evidence.md"));
    }

    /**
     * An owner that writes the evidence file replaces the fenced payload, and it is handed the counts and the same
     * enumeration page the shape assemblers list. The bugs are the raw JSON shipped beside or instead of the owner's
     * file, and an owner handed an empty context.
     */
    @Test
    void anOwnersEvidenceFileReplacesTheFencedPayload() {
        when(evidence.claimFirst(PROJECT, "f1", ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP))
                .thenReturn(new FindingEvidenceRepository.Head(List.of(WITNESS_ROW), false));
        FakeFindingAdapter owner = FakeFindingAdapter.owning("custom-x", null, null, "# Owner evidence\n");

        Map<String, String> dossier = dossierOf(FindingAdapters.of(List.of(owner)));

        assertEquals("# Owner evidence\n", dossier.get("findings/1-f1/evidence.md"));
        assertEquals(
                List.of(new EvidenceContext(
                        new EvidenceCounts(0, 0, 0, 1, 0),
                        "## Evidence enumeration\n\n"
                                + "Complete enumeration — every evidence row this finding recorded (1 total),"
                                + " `witness` first, then `baseline`, then the rest:\n\n"
                                + "- `witness` session=`s1` trace=`t1` span=`sp1`\n")),
                owner.evidenceContexts());
    }

    /**
     * An owner's evidence file longer than the dossier budget is cut at the last whole line inside it, and the cut is
     * stated. The bug is an unbounded adapter text, or one clipped with no note.
     */
    @Test
    void anOwnersEvidenceFileIsCutAtTheBudgetWithTheCutStated() {
        when(findings.findClaim(PROJECT, "f1")).thenReturn(Optional.of(claim("custom-x", "{}", "{}")));
        String line = "x".repeat(99) + "\n";
        FakeFindingAdapter owner = FakeFindingAdapter.owning("custom-x", null, null, line.repeat(2_000));

        Map<String, String> dossier = dossierOf(FindingAdapters.of(List.of(owner)));

        // The budget is 120,000 characters; the last newline at or before it ends line 1,200, at index 119,999.
        assertEquals(
                line.repeat(1_200).substring(0, 119_999)
                        + "\n\n[dossier truncated: 119,999 of 200,000 characters shown — the claim, statistics and"
                        + " population totals above this point are complete; only the tail of the enumeration list"
                        + " was cut. Page the remainder via get_finding_evidence.]\n",
                dossier.get("findings/1-f1/evidence.md"));
    }

    /**
     * A finding no adapter owns keeps its shape assembler while an adapter for another key is registered. The bug is
     * an owner's evidence hook consulted for every finding, or a frustration finding falling to the fenced JSON.
     */
    @Test
    void aFindingNoAdapterOwnsKeepsTheShapeAssembler() {
        when(findings.findClaim(PROJECT, "f1"))
                .thenReturn(Optional.of(
                        claim(BuiltInDetector.Kind.FRUSTRATION, "{\"cause_kind\":\"frustration_rate\"}", "{}")));
        FakeFindingAdapter other = FakeFindingAdapter.owning("custom-x", null, null, "# Owner evidence\n");

        Map<String, String> dossier = dossierOf(FindingAdapters.of(List.of(other)));

        assertEquals(
                "# Frustration evidence",
                Objects.requireNonNull(dossier.get("findings/1-f1/evidence.md"))
                        .lines()
                        .findFirst()
                        .orElseThrow());
        assertEquals(List.of(), other.evidenceContexts());
    }

    /** With no adapter a custom finding's RCA dossier has no {@code method.md}. */
    @Test
    void withNoAdapterACustomFindingHasNoMethodFile() {
        Map<String, String> dossier = dossierOf(FindingAdapters.of(List.of()));

        assertEquals(Set.of("findings/1-f1/finding.md", "findings/1-f1/evidence.md", "tools.md"), dossier.keySet());
    }

    /**
     * The built-in RCA method for a key comes before an adapter's, as the extension interface says. The bug is an
     * adapter that owns a built-in key replacing the method its findings are investigated by.
     */
    @Test
    void theBuiltInRcaMethodWinsOverAnAdaptersMethod() {
        when(findings.findClaim(PROJECT, "f1"))
                .thenReturn(Optional.of(claim(BuiltInDetector.Kind.TOOL_ERROR, "{\"version_id\":\"v1\"}", "{}")));

        Map<String, String> dossier = dossierOf(FindingAdapters.of(List.of(
                FakeFindingAdapter.owning(BuiltInDetector.Kind.TOOL_ERROR, "# Method x\n", "# Method x\n", null))));

        assertEquals(AgenticRcaEngine.method(BuiltInDetector.Kind.TOOL_ERROR), dossier.get("method.md"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> dossierOf(FindingAdapters adapters) {
        new RcaAnalysisService(findings, evidence, reports, agenticEngine, new ObjectMapper(), adapters).analyze(JOB);

        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        verify(agenticEngine).run(eq(JOB), eq(REPORT), any(), files.capture(), any());
        return files.getValue();
    }
}

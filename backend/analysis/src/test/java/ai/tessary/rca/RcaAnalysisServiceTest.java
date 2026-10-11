// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.finding.FakeFindingAdapter;
import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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

    private static final FindingClaim CLAIM = new FindingClaim(
            "f1",
            PROJECT,
            "custom-x",
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
            "{\"version_id\":\"v1\"}",
            "{\"witness\":1}",
            "2026-10-01T00:00:00Z");

    @Mock
    FindingRepository findings;

    @Mock
    FindingEvidenceRepository evidence;

    @Mock
    RcaReportRepository reports;

    @Mock
    AgenticRcaEngine agenticEngine;

    @BeforeEach
    void stubTheSubject() {
        when(reports.findByJobId(PROJECT, "job-1")).thenReturn(Optional.of(REPORT));
        when(findings.caseSiblingIds(PROJECT, "f1")).thenReturn(List.of());
        when(findings.findClaim(PROJECT, "f1")).thenReturn(Optional.of(CLAIM));
        when(evidence.listByFinding(PROJECT, "f1"))
                .thenReturn(List.of(new FindingEvidenceRow(
                        "ev-1",
                        PROJECT,
                        "f1",
                        "s1",
                        "t1",
                        "sp1",
                        FindingEvidenceRow.Role.WITNESS,
                        null,
                        "2026-10-01T00:00:00Z")));
        when(agenticEngine.run(eq(JOB), eq(REPORT), any(), any(), any()))
                .thenReturn(new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.NO_CAUSE_FOUND, "nothing found", List.of(), List.of(), null, false));
    }

    /** An owned finding's RCA dossier carries its adapter's {@code method.md}. */
    @Test
    void anOwnedFindingsRcaDossierCarriesItsAdaptersMethod() {
        Map<String, String> dossier =
                dossierOf(FindingAdapters.of(List.of(FakeFindingAdapter.owning("custom-x", "# Method x\n"))));

        assertEquals(
                Set.of("findings/1-f1/finding.md", "findings/1-f1/evidence.md", "method.md", "tools.md"),
                dossier.keySet());
        assertEquals("# Method x\n", dossier.get("method.md"));
    }

    /** With no adapter a custom finding's RCA dossier has no {@code method.md}. */
    @Test
    void withNoAdapterACustomFindingHasNoMethodFile() {
        Map<String, String> dossier = dossierOf(FindingAdapters.of(List.of()));

        assertEquals(Set.of("findings/1-f1/finding.md", "findings/1-f1/evidence.md", "tools.md"), dossier.keySet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> dossierOf(FindingAdapters adapters) {
        new RcaAnalysisService(findings, evidence, reports, agenticEngine, new ObjectMapper(), adapters).analyze(JOB);

        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        verify(agenticEngine).run(eq(JOB), eq(REPORT), any(), files.capture(), any());
        return files.getValue();
    }
}

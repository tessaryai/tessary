// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Four assembler shapes, one test per shape, plus the token budget and the payload
 * fallback. Real payload fixtures rather than hand-abbreviated ones — copied verbatim from {@code
 * ToolErrorEvidence.toJson}/{@code MetricFindingEvidence.toJson}'s field lists, so a field these tests
 * do not exercise is a field the real detectors also never write, not an assembler bug.
 */
class ClassifierDossierAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECT_ID = "proj";
    private static final String FINDING_ID = "f1";

    private static FindingEvidenceRow row(String role, int i) {
        return new FindingEvidenceRow(
                PROJECT_ID + "-e" + i, PROJECT_ID, FINDING_ID, null, "trace-" + i, "span-" + i, role, i, "t0");
    }

    private static FindingEvidenceRepository.Page pageOf(List<FindingEvidenceRow> rows, boolean more) {
        return new FindingEvidenceRepository.Page(rows, more ? "cursor" : null);
    }

    @Test
    void toolErrorShapeGroupsBySignatureAndDeclaresTheFlatTraceSample() throws Exception {
        String payload = """
                {"measure":"tool_error_rate","bucket":{"kind":"tool","key":"search_docs"},"direction":"up",
                 "statistic":6.1,"threshold":5.0,"criticality":42.0,"effect_size":0.3,"delta_pp":12.5,
                 "counts_basis":"onset","rate":{"ref":0.02,"cur":0.18},"n_ref":500,"n_cur":300,
                 "failures":{"cur":54},
                 "patterns":[{"signature":"timeout","source":"provider","ref":2,"cur":40},
                             {"signature":"rate_limited","source":"provider","ref":1,"cur":14}],
                 "failing_traces":["t-1","t-2","t-3"],
                 "onset_at":"2026-08-01T00:00:00Z","window":{"opened_at":"2026-08-01T00:00:00Z","closed_at":"2026-08-02T00:00:00Z","kind":"recomputed"}}
                """;
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), any(), anyInt(), any()))
                .thenReturn(pageOf(List.of(), false));

        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 0, 0), payload);

        assertTrue(out.isPresent());
        String dossier = out.get();
        assertTrue(dossier.contains("timeout"));
        assertTrue(dossier.contains("40 now, 2 before"));
        assertTrue(dossier.contains("rate_limited"));
        assertTrue(
                dossier.contains("FLAT sample, not mapped to a signature"),
                "must declare the sample, not silently list ids");
        assertTrue(dossier.contains("population since onset: n=300, failures=54"));
    }

    @Test
    void metricDriftShapeRanksTheConcentrationTableByCoveredDescending() throws Exception {
        String payload = """
                {"measure":"metric_duration","bucket":{"kind":"call_site","key":"summarize"},"reference":"pinned",
                 "w1_log":1.2,"ratio":2.4,"direction":"up","n_ref":800,"n_cur":650,"floor":0.15,
                 "quantiles":{"p50":[1200,2100],"p95":[3000,7000]},
                 "workload":{"input_tokens_p50":[500,520],"user_msg_chars_p50":[null,null],"prior_turns_p50":[2,2]},
                 "since_version_id":"v1","window":{"opened_at":"2026-08-01T00:00:00Z","closed_at":"2026-08-02T00:00:00Z","kind":"pinned"},
                 "explains":[
                   {"measure":"metric_duration","bucket":{"kind":"call_site","key":"summarize.sub_a"},"reference":"pinned",
                    "w1_log":0.4,"ratio":1.3,"direction":"up","n_cur":120,"p50_ms":[1000,1300],"covered":0.20},
                   {"measure":"metric_duration","bucket":{"kind":"call_site","key":"summarize.sub_b"},"reference":"pinned",
                    "w1_log":1.1,"ratio":2.2,"direction":"up","n_cur":400,"p50_ms":[1100,2400],"covered":0.71}
                 ]}
                """;
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), any(), anyInt(), any()))
                .thenReturn(pageOf(List.of(), false));

        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 0, 0), payload);

        assertTrue(out.isPresent());
        String dossier = out.get();
        int subB = dossier.indexOf("summarize.sub_b");
        int subA = dossier.indexOf("summarize.sub_a");
        assertTrue(
                subB >= 0 && subA >= 0 && subB < subA, "higher `covered` (sub_b, 0.71) must rank above sub_a (0.20)");
        assertTrue(dossier.contains("1200 → 2100"), "the paired before/after quantile must be rendered");
    }

    @Test
    void windowFindingShapeFallsBackToTheRawPayloadWhenNoRefCurPairExists() throws Exception {
        // A window block with no ref/cur-shaped sibling field anywhere — the generic fallback's own
        // fallback: state that plainly and include the raw payload rather than fabricate a comparison.
        String payload = "{\"window\":{\"opened_at\":\"2026-08-01T00:00:00Z\",\"closed_at\":\"2026-08-02T00:00:00Z\"},"
                + "\"note\":\"no comparable pair here\"}";
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), any(), anyInt(), any()))
                .thenReturn(pageOf(List.of(), false));

        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 0, 0), payload);

        assertTrue(out.isPresent());
        assertTrue(out.get().contains("No paired before/after fields found"));
    }

    @Test
    void windowFindingShapePairsAGenericRefCurField() throws Exception {
        String payload =
                "{\"window\":{\"opened_at\":\"a\",\"closed_at\":\"b\"}," + "\"latency_ms\":{\"ref\":100,\"cur\":900}}";
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), any(), anyInt(), any()))
                .thenReturn(pageOf(List.of(), false));

        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 0, 0), payload);

        assertTrue(out.isPresent());
        assertTrue(out.get().contains("latency_ms: 100 → 900"));
    }

    @Test
    void unrecognisedShapeFallsThroughToEmpty() throws Exception {
        String payload = "{\"some_other_classifier\":true,\"value\":1}";
        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER,
                mock(FindingEvidenceRepository.class),
                PROJECT_ID,
                FINDING_ID,
                new EvidenceCounts(0, 0, 0, 0, 0),
                payload);
        assertTrue(
                out.isEmpty(),
                "no dedicated assembler recognises this shape — caller must fall back to DossierPayload.forAgent");
    }

    @Test
    void smallEvidenceSetIsFullyEnumerated() throws Exception {
        String payload = "{\"bucket\":{\"key\":\"k\"},\"ratio\":1.0,\"window\":{}}";
        List<FindingEvidenceRow> rows =
                List.of(row(FindingEvidenceRow.Role.MEMBER, 1), row(FindingEvidenceRow.Role.MEMBER, 2));
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(
                        eq(PROJECT_ID),
                        eq(FINDING_ID),
                        any(),
                        eq(ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP),
                        any()))
                .thenReturn(pageOf(rows, false));

        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 2, 0, 0, 0), payload);

        assertTrue(out.isPresent());
        String dossier = out.get();
        assertTrue(dossier.contains("Complete enumeration"));
        assertTrue(dossier.contains("trace-1"));
        assertTrue(dossier.contains("trace-2"));
        assertFalse(dossier.contains("DECLARED SELECTION"));
    }

    @Test
    void largeEvidenceSetDeclaresASelectionAndStatesPopulationCounts() throws Exception {
        String payload = "{\"bucket\":{\"key\":\"k\"},\"ratio\":1.0,\"window\":{}}";
        List<FindingEvidenceRow> page = new ArrayList<>();
        for (int i = 0; i < ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP; i++) {
            page.add(row(FindingEvidenceRow.Role.MEMBER, i));
        }
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(
                        eq(PROJECT_ID),
                        eq(FINDING_ID),
                        any(),
                        eq(ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP),
                        any()))
                .thenReturn(pageOf(page, true)); // more rows exist beyond this page

        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 50_000, 0, 0, 0), payload);

        assertTrue(out.isPresent());
        String dossier = out.get();
        assertTrue(dossier.contains("DECLARED SELECTION"));
        assertTrue(dossier.contains("50,000") || dossier.contains("50000"), "population total must be stated");
        assertTrue(dossier.contains(String.valueOf(ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP)));
    }

    @Test
    void noEvidenceRowsIsStatedPlainly() throws Exception {
        String payload = "{\"bucket\":{\"key\":\"k\"},\"ratio\":1.0,\"window\":{}}";
        Optional<String> out = ClassifierDossierAssembler.assemble(
                MAPPER,
                mock(FindingEvidenceRepository.class),
                PROJECT_ID,
                FINDING_ID,
                new EvidenceCounts(0, 0, 0, 0, 0),
                payload);
        assertTrue(out.isPresent());
        assertTrue(out.get().contains("No evidence rows recorded"));
    }

    @Test
    void nullOrBlankPayloadIsEmpty() {
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        assertTrue(ClassifierDossierAssembler.assemble(
                        MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 0, 0), null)
                .isEmpty());
        assertTrue(ClassifierDossierAssembler.assemble(
                        MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 0, 0), "  ")
                .isEmpty());
    }

    @Test
    void budgetTruncatesOnlyTheTailAndStatesTheCutPlainly() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 20_000; i++) huge.append("line ").append(i).append('\n');
        String truncated = ClassifierDossierAssembler.budget(huge.toString());

        assertTrue(truncated.length() < huge.length());
        assertTrue(truncated.contains("[dossier truncated:"));
        assertTrue(truncated.startsWith("line 0\n"), "the head — the claim and statistics — must survive whole");
    }

    @Test
    void budgetIsANoOpUnderTheCap() {
        String small = "line 1\nline 2\n";
        assertEquals(small, ClassifierDossierAssembler.budget(small));
    }

    /** Sanity on the fixture's own numeral formatting, so the "50,000" assertion above is not brittle
     *  to locale — pinned separately here rather than relying on the test JVM's default locale. */
    @Test
    void countFormattingUsesRootLocale() {
        assertEquals("50,000", String.format(Locale.ROOT, "%,d", 50_000));
    }
}

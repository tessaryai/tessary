// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), anyInt())).thenReturn(pageOf(List.of(), false));

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
    void frustrationShapeStatesTheRateInConversationsAndPairsSessionWithTraceWitnesses() throws Exception {
        String payload = """
                {"call_site_id":"support-chat","direction":"up","baseline_conversations":200,
                 "baseline_frustrated":10,"baseline_rate":0.052,"current_rate":0.25,
                 "conversations_since_onset":600,"frustrated_since_onset":150,"delta_pp":19.8,
                 "effect_size":0.6,"statistic":7.2,"threshold":4.94,"criticality":43.6,
                 "onset_at":"2026-08-01T10:00:00Z","arl_target":10000,"min_decision_interval":4.0,
                 "scorer_version":"jev-choice3-abc","jev_threshold":0.4,
                 "cause_kind":"frustration_rate","workflow_key":"","native_cause_key":"support-chat"}
                """;
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), anyInt()))
                .thenReturn(pageOf(
                        List.of(
                                new FindingEvidenceRow(
                                        "e1", PROJECT_ID, FINDING_ID, "conv-1", null, null, "witness", 0, "t0"),
                                new FindingEvidenceRow(
                                        "e2", PROJECT_ID, FINDING_ID, null, "trace-1", null, "witness", 1, "t0")),
                        false));

        String dossier = ClassifierDossierAssembler.assemble(
                        MAPPER, evidence, PROJECT_ID, FINDING_ID, new EvidenceCounts(0, 0, 0, 2, 0), payload)
                .orElseThrow();

        assertTrue(dossier.startsWith("# Frustration evidence"));
        assertTrue(dossier.contains("5.20% learned → 25.00% since onset"));
        assertTrue(dossier.contains("since onset: 600 conversations, 150 frustrated"));
        assertTrue(dossier.contains("There is no baseline side"));
        assertTrue(dossier.contains("- `witness` session=`conv-1` trace=`-`"));
        assertTrue(dossier.contains("- `witness` trace=`trace-1`"));
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
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), anyInt())).thenReturn(pageOf(List.of(), false));

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
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), eq(ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP)))
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
        when(evidence.page(eq(PROJECT_ID), eq(FINDING_ID), eq(ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP)))
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

    /** A payload that is not JSON has no shape to assemble, so the caller falls back rather than failing the run. */
    @Test
    void anUnparseablePayloadFallsThroughToEmpty() {
        assertEquals(
                Optional.empty(),
                ClassifierDossierAssembler.assemble(
                        MAPPER,
                        mock(FindingEvidenceRepository.class),
                        PROJECT_ID,
                        FINDING_ID,
                        new EvidenceCounts(0, 0, 0, 0, 0),
                        "{not json"));
    }

    /** Each role reads its own count, so the declared population never swaps one side for another. */
    @ParameterizedTest
    @CsvSource({"exemplar, 1", "member, 2", "baseline, 3", "witness, 4", "changepoint, 5", "sample, 0"})
    void eachRoleReadsItsOwnCount(String role, long expected) {
        assertEquals(expected, new EvidenceCounts(1, 2, 3, 4, 5).forRole(role));
    }

    private static final String TOOL_ERROR_HEAD = "# Tool error evidence\n\n"
            + "- tool: `search_docs`\n"
            + "- direction: up\n"
            + "- rate: 2.00% → 18.00% (CUSUM 6.10 past a 5.00 decision interval)\n"
            + "- population since onset: n=300, failures=54\n"
            + "\n## Failure patterns, grouped by error signature\n\n";

    private static final String TOOL_ERROR_BASE = "\"bucket\":{\"key\":\"search_docs\"},\"direction\":\"up\","
            + "\"rate\":{\"ref\":0.02,\"cur\":0.18},\"statistic\":6.1,\"threshold\":5.0,\"n_cur\":300,"
            + "\"failures\":{\"cur\":54}";

    /**
     * A cut pattern list says it was cut, so the agent does not read the kept patterns as the whole failure
     * population; a payload with no patterns says there is no breakdown, so the agent does not invent one.
     */
    @Test
    void theToolErrorDossierDeclaresACutPatternListAndAMissingOne() throws Exception {
        assertEquals(
                TOOL_ERROR_HEAD
                        + "Every pattern the detector tracked for this tool, ranked in the detector's own order"
                        + " (patterns.json's own array order — not re-sorted here). `cur` is the count"
                        + " since onset; `ref` is the same signature's count in the prior window, so a"
                        + " signature with ref=0 is new.\n\n"
                        + "- `timeout` (provider): 40 now, 2 before\n"
                        + "\n(patterns_truncated=true — the detector's own pattern list was cut; the counts"
                        + " above cover only the patterns it kept, not the tool's whole failure"
                        + " population. `failures.cur` above is still the true total.)\n",
                ToolErrorDossier.build(MAPPER.readTree("{" + TOOL_ERROR_BASE
                        + ",\"patterns\":[{\"signature\":\"timeout\",\"source\":\"provider\",\"ref\":2,\"cur\":40}],"
                        + "\"patterns_truncated\":true}")));
        assertEquals(
                TOOL_ERROR_HEAD
                        + "No per-signature breakdown in this payload — only the aggregate rate above. State"
                        + " that plainly rather than inventing signatures; get_finding_evidence still"
                        + " pages the raw failing calls.\n",
                ToolErrorDossier.build(MAPPER.readTree("{" + TOOL_ERROR_BASE + "}")));
    }

    /**
     * The rolling arm has no per-instance baseline rows, so its token pairs and the ring it was learned from
     * are the only account of the reference side. Dropping either leaves the agent auditing a claim with no
     * "before" to compare against.
     */
    @Test
    void theMetricDriftDossierCarriesTheTokenPairsAndTheReferenceRing() throws Exception {
        String payload = "{\"bucket\":{\"key\":\"summarize\",\"kind\":\"call_site\"},\"reference\":\"previous\","
                + "\"direction\":\"up\",\"w1_log\":0.5,\"ratio\":1.5,\"floor\":0.15,\"n_ref\":100,\"n_cur\":90,"
                + "\"tokens\":{\"input_p50\":[500,800],\"output_p50\":[null,null]},"
                + "\"control\":{\"days_used\":6,\"days_excluded_as_confirmed\":1,\"oldest_day\":\"2026-08-01\"}}";

        assertEquals(
                "# Metric drift evidence\n\n"
                        + "- bucket: `summarize` (call_site)\n"
                        + "- reference: previous\n"
                        + "- direction: up\n"
                        + "- effect: w1_log=0.5000 ratio=1.5000x (floor 0.1500)\n"
                        + "- population: n_ref=100, n_cur=90\n"
                        + "\n## Reference vs. current (the paired before/after aggregate)\n\n"
                        + "- input_p50: 500 → 800\n"
                        + "\nReference composition (rolling arm — no per-instance baseline rows exist for this arm,"
                        + " only this ring): 6 day(s) used, 1 excluded as already-confirmed, oldest day 2026-08-01.\n"
                        + "\n## Concentration: which sibling buckets this drift explains\n\n"
                        + "This shift explains no sibling buckets (explains=[] or absent) — it did not"
                        + " suppress any other finding, so there is nothing to concentrate over.\n",
                MetricDriftDossier.build(MAPPER.readTree(payload)));
    }
}

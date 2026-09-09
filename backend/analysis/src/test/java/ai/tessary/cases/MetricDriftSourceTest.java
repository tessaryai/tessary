// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The gate, and what happens when a shift stops.
 *
 * <p>Both properties here are the ones that decide whether Triage is a screen anyone trusts. Metric
 * drift writes findings with <b>no alert budget</b> — every shift past the floor gets a row — so the
 * only thing standing between that stream and the page people get paged from is
 * {@link MetricDriftSource}'s triage gate. And because the source hands
 * {@link CaseReconciler} a live set rather than a delta, a bucket that recovers has to <em>fall out</em>
 * of {@link MetricDriftSource#detect}; a source that kept returning it would leave every case it ever
 * opened to be closed by hand.
 *
 * <p>The gate itself is a SQL predicate — deliberately, so the row limit bounds live findings rather
 * than being spent on withheld ones — and {@link MetricDriftCaseGateIntegrationTest} is what proves it
 * against Postgres. What these mocks assert is the half above that line: that this source asks for the
 * gated set, adds no path around it, and turns a row into a detection without inventing a number.
 */
class MetricDriftSourceTest {

    private static final String PROJECT = "prj_1";
    private static final String BUCKET = "discover-sales-prospects";
    private static final String CAUSE_KEY = "turn_duration:" + BUCKET + ":slower:pinned";

    private final FindingRepository findings = mock(FindingRepository.class);
    private final ClassifierRepository signals = mock(ClassifierRepository.class);
    private final MetricDriftSource source = new MetricDriftSource(findings, signals, new ObjectMapper());

    @Test
    void anTriagedShiftOpensACaseAndRecoveryClosesItOnTheNextPass() {
        when(signals.listByProject(PROJECT)).thenReturn(List.of());
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of(finding(FindingRow.TriageVerdict.POSITIVE, FindingRow.Status.OPEN)));

        List<CaseDetection> firing = source.detect(PROJECT);

        assertEquals(1, firing.size());
        CaseDetection detection = firing.get(0);
        assertEquals(CaseRow.Detector.METRIC_DRIFT, detection.key().detector());
        assertEquals(CaseRow.SubjectKind.METRIC_BASELINE, detection.key().subjectKind());
        assertEquals(CAUSE_KEY, detection.key().subjectId());
        assertEquals("turn_duration", detection.key().metric());
        // A sentence, not a metric. 0.34 in log units is e^0.34 = 1.40x.
        assertEquals(BUCKET + " turns are 1.40× slower", detection.title());
        assertTrue(
                detection.basis().contains("pinned at the last deploy"),
                "the basis must say which reference this crossed: " + detection.basis());
        assertTrue(
                detection.basis().contains("W₁ 0.34"),
                "the basis must cite this detector's own bar, unnormalized: " + detection.basis());
        // The medians in raw milliseconds, [then, now] — never a percentage.
        assertEquals(2940.0, detection.currentValue());
        assertEquals(2100.0, detection.baselineValue());
        assertEquals(840.0, detection.delta());
        assertNotNull(detection.onsetAt());

        // The bucket returns to its reference: nothing writes "recovered", the finding simply stops being
        // bumped and drops out of the live query. The reconciler closes the case from that absence, which
        // only works because this source restates the whole live set every pass.
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertTrue(source.detect(PROJECT).isEmpty(), "a recovered shift must leave the live set");
    }

    /**
     * The one that keeps Triage quiet. An un-triaged finding is a LEAD — Layer 1 detects change and
     * cannot tell change from a problem, and at this classifier's operating point most of what it flags
     * is legitimate. It belongs on the Classifiers page for a human to absorb or dismiss, and nowhere
     * near a list whose job is to be empty.
     */
    @Test
    void anUntriagedFindingOpensNoCase() {
        when(signals.listByProject(PROJECT)).thenReturn(List.of());
        // A project whose only finding is an un-triaged lead: the gated query hands back nothing.
        // MetricDriftCaseGateIntegrationTest is what proves the predicate itself withholds that lead
        // against real SQL; what this asserts is that the source has no second path around it — no
        // "show it anyway if it has recurred enough", no ungated fallback list.
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertTrue(source.detect(PROJECT).isEmpty());

        verify(findings, never()).listByProject(anyString(), any(), any(), any(), anyBoolean(), anyInt());
    }

    /**
     * The human arm of the gate. Pressing <em>Real deviation</em> sets {@code blocked} and zeroes
     * {@code recurrences_since_verdict}, so a case has to open on the verdict itself rather than waiting
     * for the bucket to shift again — a person who has just confirmed a regression should not have to see
     * it happen twice more.
     */
    @Test
    void aHumanConfirmedShiftOpensACaseWithNoMachineVerdict() {
        when(signals.listByProject(PROJECT)).thenReturn(List.of());
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of(finding(null, FindingRow.Status.BLOCKED)));

        CaseDetection detection = source.detect(PROJECT).get(0);

        assertTrue(
                detection.basis().startsWith("A human ruled this a real deviation."),
                "the basis must say a person decided this, not a model: " + detection.basis());
    }

    /**
     * <b>Launch requirement B7.</b> A case says who confirmed it, so nobody reads a machine ruling as a
     * human one. Two authorities now, not three lanes: a person pressed <em>Real deviation</em>, or a
     * triage run audited the claim and found it sound.
     */
    @Test
    void theCaseNamesWhoConfirmedIt() {
        when(signals.listByProject(PROJECT)).thenReturn(List.of());
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of(finding(FindingRow.TriageVerdict.POSITIVE, FindingRow.Status.OPEN)));

        String basis = source.detect(PROJECT).get(0).basis();

        assertTrue(basis.startsWith("A triage run audited this claim and found it sound."), basis);
    }

    /** A human ruling outranks the machine's and says so in its own words. */
    @Test
    void aHumanRulingSaysSo() {
        when(signals.listByProject(PROJECT)).thenReturn(List.of());
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of(finding(null, FindingRow.Status.BLOCKED)));

        String basis = source.detect(PROJECT).get(0).basis();

        assertTrue(basis.startsWith("A human ruled this a real deviation."), basis);
    }

    /**
     * A confirmed regression whose evidence blob cannot be parsed still reaches Triage. Dropping it would
     * be the worst available outcome — it is the one finding a human has already said is real — so the
     * case opens and says the numbers are unavailable instead of showing invented ones.
     */
    @Test
    void anUnreadableEvidenceBlobDegradesRatherThanDroppingTheCase() {
        when(signals.listByProject(PROJECT)).thenReturn(List.of());
        when(findings.listSurvivingAnalysis(eq(PROJECT), anyCollection(), any(), anyString(), anyInt()))
                .thenReturn(List.of(row(FindingRow.TriageVerdict.POSITIVE, FindingRow.Status.OPEN, "{not json")));

        CaseDetection detection = source.detect(PROJECT).get(0);

        // The lane still comes off the cause key, so a bad blob cannot make this open a SECOND case for a
        // regression that already had one.
        assertEquals("turn_duration", detection.key().metric());
        assertEquals(CAUSE_KEY, detection.key().subjectId());
        assertEquals(0.5, detection.severity(), "an unreadable blob is not evidence of a small move");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static FindingRow finding(@Nullable String verdict, String status) {
        return row(verdict, status, EVIDENCE);
    }

    /** PROGRAM.md §7's blob, as {@code MetricFindingEvidence} writes it. */
    private static final String EVIDENCE = """
            {"measure":"turn_duration","bucket":{"kind":"call_site","key":"discover-sales-prospects"},
             "reference":"pinned","w1_log":0.34,"ratio":1.4049,"direction":"up","n_ref":4210,"n_cur":1180,
             "quantiles":{"p50":[2100,2940],"p95":[9000,21400]},
             "workload":{"input_tokens_p50":[1240,1290],"user_msg_chars_p50":[88,91],"prior_turns_p50":[3,3]},
             "since_version_id":null,
             "window":{"opened_at":"2026-07-28T00:00:00Z","closed_at":"2026-07-30T00:00:00Z","kind":"count"}}
            """;

    private static FindingRow row(@Nullable String verdict, String status, String evidence) {
        return new FindingRow(
                "fnd_1",
                PROJECT,
                BuiltInDetector.Kind.DURATION_DRIFT,
                "mbl_1:" + CAUSE_KEY,
                FindingRow.SubjectKind.METRIC_BASELINE,
                "mbl_1",
                BUCKET,
                null,
                status,
                "2026-07-30T00:00:00Z",
                Instant.now().toString(),
                null,
                null,
                null,
                1180,
                withVocabulary(evidence),
                /* evidenceCountsJson */ null,
                /* sinceVersionId */ null,
                verdict == null ? null : "2026-07-30T06:00:00Z",
                verdict,
                verdict == null ? null : FindingRow.TriageAction.of(verdict),
                verdict == null ? null : "The retry loop added in 4f2a1c explains it.",
                /* triageCitationsJson */ null,
                verdict == null ? null : "2026-07-30T06:30:00Z",
                FindingRow.Status.BLOCKED.equals(status) ? "2026-07-30T12:00:00Z" : null,
                0,
                "2026-07-30T00:00:00Z",
                Instant.now().toString());
    }

    /**
     * The detector's blob with the native vocabulary merged in, exactly as {@code FindingRepository}
     * writes it. Spelled here rather than assumed, because {@code causeKind()} reads out of the payload
     * now and a fixture that skipped it would exercise a shape the writer never produces.
     */
    private static String withVocabulary(String evidence) {
        String vocab = "{\"cause_kind\":\"" + FindingRow.Cause.DISTRIBUTION_SHIFT
                + "\",\"workflow_key\":\"__global__\",\"native_cause_key\":\"" + CAUSE_KEY + "\"}";
        String trimmed = evidence.trim();
        // The same splice FindingRepository#mergeVocabulary does, INCLUDING its refusal to splice a blob
        // that is not an object: an unreadable blob still leaves a finding that knows its own cause.
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return vocab;
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) return vocab;
        return "{" + body + "," + vocab.substring(1);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingRowBuilder;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link MetricDriftSource#shape}: what {@link CaseOpener} calls once a finding already qualifies for a
 * case (a triage positive, or a human's "Real deviation") — this class has no gate of its own any more,
 * so there is nothing here to assert about which findings reach it. What these tests pin is that the
 * shape it produces is correct: the case key doesn't collide across causes, the numbers come straight
 * off the finding's own evidence rather than being invented, and the basis names whichever authority
 * ruled.
 */
class MetricDriftCaseShapeTest {

    private static final String BUCKET = "discover-sales-prospects";
    private static final String CAUSE_KEY = "turn_duration:" + BUCKET + ":slower:pinned";

    private final MetricDriftSource source = new MetricDriftSource();

    @Test
    void ownsDurationAndCostDriftOnly() {
        assertTrue(source.owns(BuiltInDetector.Kind.DURATION_DRIFT));
        assertTrue(source.owns(BuiltInDetector.Kind.COST_DRIFT));
        assertFalse(source.owns(BuiltInDetector.Kind.TOOL_ERROR));
        assertFalse(source.owns(BuiltInDetector.Kind.SECRET_LEAK));
    }

    @Test
    void shapesTheKeyAndTheNumbersOffTheFindingsOwnEvidence() {
        CaseDetection detection = source.shape(finding(FindingRow.TriageVerdict.POSITIVE, false));

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
    }

    /**
     * <b>Launch requirement B7.</b> A case says who confirmed it, so nobody reads a machine ruling as a
     * human one. Two authorities now, not three lanes: a person pressed <em>Real deviation</em>, or a
     * triage run audited the claim and found it sound.
     */
    @Test
    void theCaseNamesWhoConfirmedIt() {
        String machineBasis =
                source.shape(finding(FindingRow.TriageVerdict.POSITIVE, false)).basis();
        assertTrue(machineBasis.startsWith("A triage run audited this claim and found it sound."), machineBasis);

        String humanBasis =
                source.shape(finding(FindingRow.TriageVerdict.POSITIVE, true)).basis();
        assertTrue(humanBasis.startsWith("A human ruled this a real deviation."), humanBasis);
    }

    /**
     * A confirmed regression whose evidence blob cannot be parsed still reaches Triage. Dropping it would
     * be the worst available outcome — it is the one finding a human has already said is real — so the
     * case opens and says the numbers are unavailable instead of showing invented ones.
     */
    @Test
    void anUnreadableEvidenceBlobDegradesRatherThanDroppingTheCase() {
        CaseDetection detection = source.shape(row(FindingRow.TriageVerdict.POSITIVE, false, "{not json"));

        // The key still comes off the cause key, so a bad blob cannot make this open a SECOND case for a
        // regression that already had one.
        assertEquals("turn_duration", detection.key().metric());
        assertEquals(CAUSE_KEY, detection.key().subjectId());
        assertEquals(0.5, detection.severity(), "an unreadable blob is not evidence of a small move");
    }

    /**
     * {@code onset_at} is a text column. An onset the parser cannot read (blank, or Postgres's own
     * timestamp rendering) leaves the case unbracketed; throwing there fails the open of a confirmed
     * regression.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "2026-07-30 00:00:00+00"})
    void anOnsetThatIsNotAnInstantOpensTheCaseUnbracketed(String onsetAt) {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT)
                .causeKey("mbl_1:" + CAUSE_KEY)
                .onsetAt(onsetAt)
                .payload(withVocabulary(EVIDENCE))
                .build();

        assertNull(source.shape(finding).onsetAt());
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static FindingRow finding(@Nullable String verdict, boolean human) {
        return row(verdict, human, EVIDENCE);
    }

    /** metric-drift.md §7's blob, as {@code MetricFindingEvidence} writes it. */
    private static final String EVIDENCE = """
            {"measure":"turn_duration","bucket":{"kind":"call_site","key":"discover-sales-prospects"},
             "reference":"pinned","w1_log":0.34,"ratio":1.4049,"direction":"up","n_ref":4210,"n_cur":1180,
             "quantiles":{"p50":[2100,2940],"p95":[9000,21400]},
             "workload":{"input_tokens_p50":[1240,1290],"user_msg_chars_p50":[88,91],"prior_turns_p50":[3,3]},
             "since_version_id":null,
             "window":{"opened_at":"2026-07-28T00:00:00Z","closed_at":"2026-07-30T00:00:00Z","kind":"count"}}
            """;

    private static final String PROJECT = "prj_1";

    private static FindingRow row(@Nullable String verdict, boolean human, String evidence) {
        return new FindingRow(
                "fnd_1",
                PROJECT,
                BuiltInDetector.Kind.DURATION_DRIFT,
                "mbl_1:" + CAUSE_KEY,
                FindingRow.SubjectKind.METRIC_BASELINE,
                "mbl_1",
                BUCKET,
                null,
                FindingRow.Status.OPEN,
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
                human ? "2026-07-30T12:00:00Z" : null,
                /* caseId */ null,
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricFindingEvidence.Explains;
import ai.tessary.classifier.metric.MetricFindingEvidence.Read;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link MetricFindingEvidence#detail} on the fields R3 added: {@code bucket.kind}, {@code
 * window.kind}, {@code since_version_id}, the rolling reference's {@code control} block, and {@code
 * explains}. Fixed on a hand-written blob rather than {@link MetricFindingEvidence#toJson}'s own
 * output, because that write path needs a {@link MetricSketch}/{@link MetricWorkload}/{@link
 * MetricControl.Resolved} graph this test has no stake in building — the reader is what changed.
 */
class MetricFindingEvidenceTest {

    private static final String ROLLING_ARM_BLOB = """
            {"measure":"turn_duration","bucket":{"kind":"call_site","key":"cs-1"},
             "reference":"previous","direction":"up","ratio":1.42,"w1_log":0.35,"floor":0.19,
             "n_ref":500,"n_cur":80,
             "quantiles":{"p50":[2000.0,2840.0],"p95":[4000.0,5680.0]},
             "workload":{"input_tokens_p50":[1200.0,1250.0]},
             "since_version_id":"v-42",
             "control":{"days_used":14,"days_excluded_as_confirmed":2,"oldest_day":"2026-05-01",
                        "half_life_days":7.0,"retain_days":21},
             "window":{"opened_at":"2026-06-01T00:00:00Z","closed_at":"2026-06-02T00:00:00Z","kind":"count"},
             "explains":[{"measure":"tool_duration","bucket":{"kind":"tool","key":"tool:search_docs"},
                          "reference":"previous","w1_log":0.4,"ratio":1.5,"direction":"up","n_cur":50,
                          "p50_ms":[1500.0,4500.0],"covered":1.0}]}
            """;

    @Test
    void detail_parsesTheControlBlockAndExplains() {
        ShiftDetail detail = MetricFindingEvidence.detail(ROLLING_ARM_BLOB);

        assertNotNull(detail);
        assertEquals("call_site", detail.bucketKind());
        assertEquals("count", detail.windowKind());
        assertEquals("v-42", detail.sinceVersionId());

        assertNotNull(detail.control(), "the rolling arm reports how its reference was composed");
        assertEquals(14, detail.control().daysUsed());
        assertEquals(2, detail.control().daysExcludedAsConfirmed());
        assertEquals("2026-05-01", detail.control().oldestDay());
        assertEquals(7.0, detail.control().halfLifeDays());
        assertEquals(21, detail.control().retainDays());

        assertEquals(1, detail.explains().size(), "the suppressed tool shift rode along");
        Explains explained = detail.explains().get(0);
        assertEquals("tool_duration", explained.measure());
        assertEquals("tool", explained.bucketKind());
        assertEquals("tool:search_docs", explained.bucketKey());
        assertEquals("up", explained.direction());
        assertEquals(50, explained.nCur());
        assertEquals(1500.0, explained.refMillis());
        assertEquals(4500.0, explained.curMillis());
        assertEquals(1.0, explained.covered());
    }

    /** The headline a case is built from: the direction read as written, and the p50 pair split into its sides. */
    @Test
    void read_takesTheHeadlineNumbersAndTheMedianPair() {
        assertEquals(
                new Read(
                        "turn_duration",
                        "cs-1",
                        "previous",
                        0.35,
                        1.42,
                        Direction.UP,
                        80,
                        OptionalDouble.of(2000.0),
                        OptionalDouble.of(2840.0)),
                MetricFindingEvidence.read(ROLLING_ARM_BLOB));
        Read down = MetricFindingEvidence.read(
                "{\"measure\":\"cost\",\"bucket\":{\"key\":\"cs-2\"},\"direction\":\"down\",\"quantiles\":{}}");
        assertNotNull(down);
        assertEquals(Direction.DOWN, down.direction(), "a fall read as a rise would page on the wrong side");
        assertEquals(OptionalDouble.empty(), down.refP50(), "an absent pair is unavailable, not a 0 ms median");
    }

    /**
     * A blob that is empty, unreadable, or missing its measure or bucket is unavailable to both readers:
     * null, never an exception that 500s the case page and never a zeroed record shown as the finding's own.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "   ",
                "{not json",
                "{\"bucket\":{\"key\":\"cs-1\"}}",
                "{\"measure\":\"turn_duration\",\"bucket\":{}}"
            })
    void anUnreadableOrKeylessBlobIsUnavailableToBothReaders(@Nullable String blob) {
        assertNull(MetricFindingEvidence.read(blob));
        assertNull(MetricFindingEvidence.detail(blob));
    }
}

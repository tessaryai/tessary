// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricFindingEvidence.ChangePoint;
import ai.tessary.classifier.metric.MetricFindingEvidence.Explains;
import ai.tessary.classifier.metric.MetricFindingEvidence.Pair;
import ai.tessary.classifier.metric.MetricFindingEvidence.PlainShift;
import ai.tessary.classifier.metric.MetricFindingEvidence.Read;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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
        assertNull(detail.mean(), "a blob written before the mean existed reads it as absent");
        assertNull(detail.shift(), "and its plain-unit shift too, rather than crashing on every older finding");
    }

    /**
     * The shift in the measure's own units. On a grid of doubling bins (bin i is [2^i, 2^(i+1)), raw midpoint 2^(i +
     * 0.5)), two rows in each of bins 0 and 1 against four rows in bin 2 give a mean of (2 x 1.4142 + 2 x 2.8284) / 4
     * = 2.12 then and 5.66 now, so the mean rose by 3.54. The quantiles interpolate inside a bin: p50 2.0 to 5.66 and
     * p95 2^1.9 = 3.73 to 2^2.95 = 7.73, so they rose by 3.66 and 4.0. The bug is a shift stated only as a ratio, or a
     * mean taken from rows on one side and from the sketch on the other.
     */
    @Test
    void payloadStatesTheMeanAndASignedShift() throws Exception {
        Grid grid = new Grid(1.0, 2.0, 4);
        MetricHistogram ref = new MetricHistogram(grid);
        ref.add(Math.log(1.2));
        ref.add(Math.log(1.2));
        ref.add(Math.log(2.5));
        ref.add(Math.log(2.5));
        MetricHistogram cur = new MetricHistogram(grid);
        for (int i = 0; i < 4; i++) cur.add(Math.log(5));
        Decision fired =
                new Decision(true, "turn_duration", Reference.PINNED, 0.98, 2.67, Direction.UP, 4, 4, 0.19, null);

        String json = MetricFindingEvidence.toJson(
                "turn_duration",
                "call_site",
                "cs-1",
                fired,
                ref,
                cur,
                null,
                null,
                null,
                null,
                null,
                "2026-06-01T00:00:00Z",
                "2026-06-01T00:00:07Z",
                "count",
                List.of(),
                null,
                grid,
                new ChangePoint("2026-06-01T00:00:04Z", ChangePoint.METHOD_CUSUM, 4, 4),
                "2026-05-25T00:00:00Z");

        JsonNode root = MetricHistogram.JSON.readTree(json);
        assertEquals(2.12, root.path("mean").path(0).asDouble());
        assertEquals(5.66, root.path("mean").path(1).asDouble());
        assertEquals(3.54, root.path("shift").path("mean").asDouble());
        assertEquals(3.66, root.path("shift").path("p50").asDouble());
        assertEquals(4.0, root.path("shift").path("p95").asDouble());
        JsonNode changepoint = root.path("changepoint");
        assertEquals("2026-06-01T00:00:04Z", changepoint.path("at").asText());
        assertEquals("cusum", changepoint.path("method").asText());
        assertEquals(4, changepoint.path("index").asInt());
        assertEquals(4, changepoint.path("rows_before").asInt());
        assertEquals("2026-05-25T00:00:00Z", root.path("reference_from").asText());

        ShiftDetail detail = MetricFindingEvidence.detail(json);
        assertNotNull(detail);
        assertEquals(new Pair("mean", 2.12, 5.66), detail.mean());
        assertEquals(new PlainShift(3.54, 3.66, 4.0), detail.shift());
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

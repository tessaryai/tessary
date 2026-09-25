// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftConfig.Measured;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parsing a metric-drift classifier's blob. The bugs are a configured bin count the sweep does not lay its
 * grids on, a measure name this build does not know taking the sweep down, and a blob that will not parse
 * dead-lettering the sweep instead of running at the defaults.
 */
class MetricDriftConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * An edited {@code hist_bins} re-lays every measured grid on that count, keeping {@code lo} and the
     * ratio; an unknown or repeated measure name is dropped rather than thrown on.
     */
    @Test
    void aConfiguredBinCountReLaysTheMeasuredGridAndUnknownMeasuresAreDropped() {
        MetricDriftConfig c = MetricDriftConfig.of(
                MAPPER, "{\"measures\":[\"turn_duration\",\"latency_p99\",\"turn_duration\",7],\"hist_bins\":128}");

        Grid duration = Grid.duration();
        assertEquals(List.of(Measure.TURN_DURATION), c.measures());
        assertEquals(
                List.of(new Measured(
                        Measure.TURN_DURATION,
                        Grain.TURN,
                        MetricBaselineRow.BucketKind.CALL_SITE,
                        new Grid(duration.lo(), duration.ratio(), 128))),
                c.measured());
    }

    /** At the default bin count the registry's own grid is used as is. */
    @Test
    void theDefaultBinCountKeepsTheRegistryGrid() {
        assertEquals(
                Grid.duration(), MetricDriftConfig.defaults().measured().get(0).grid());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {"  ", "{not json", "{\"measures\":\"turn_duration\",\"min_sample\":null,\"w1_floor\":null}"})
    void anAbsentUnreadableOrNullFieldFallsBackToTheDefaults(@Nullable String blob) {
        assertEquals(MetricDriftConfig.defaults(), MetricDriftConfig.of(MAPPER, blob));
    }
}

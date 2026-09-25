// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricDriftDetector;
import ai.tessary.classifier.metric.MetricHistogram;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The metric-drift dial: what {@code GET/PUT .../tuning} reads and writes, and the false-alarm rate it reports
 * beside the dial. The rate is {@link MetricDriftDetector#impliedFalseAlarmRate} at the MEDIAN readable spread
 * of the buckets this classifier watches; these tests pin which spread that is, not the noise law itself.
 */
class ClassifierTuningTest {

    private static final String PID = "proj-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClassifierRepository signals = mock(ClassifierRepository.class);
    private final MetricBaselineRepository baselines = mock(MetricBaselineRepository.class);
    private final ClassifierService service = new ClassifierService(
            signals,
            mock(ClassifierDetectionRepository.class),
            mock(ClassifierJobRepository.class),
            mock(BuiltInClassifierCatalog.class),
            mock(SubstrateReadRepository.class),
            MAPPER,
            new ClassifierProperties(),
            mock(CapabilityService.class),
            mock(ProjectRepository.class),
            baselines,
            mock(ai.tessary.llm.decisions.DecisionProviderResolver.class),
            mock(ai.tessary.plan.EncoderAvailability.class),
            new ai.tessary.config.GroundednessProperties());

    /**
     * Catches the rate being read off the mean spread (one pathological bucket would set it), off the first
     * bucket, or off nothing once one baseline's sketch is unreadable; and a bucket that has not been pinned
     * yet being skipped when its newest closed day has a sketch to read.
     */
    @Test
    void theImpliedRateReadsTheMedianReadableSpread() {
        // Spreads of about 0.75, 1.0 and 1.3: all inside the span the noise law interpolates over, so each
        // implies a different rate and picking the wrong bucket shows.
        MetricHistogram tight = sketch(4.0, 5.5);
        MetricHistogram middle = sketch(3.5, 5.5);
        MetricHistogram wide = sketch(3.2, 5.8);
        when(signals.findById(PID, "clf-cost"))
                .thenReturn(Optional.of(row("clf-cost", BuiltInDetector.Kind.COST_DRIFT, null)));
        when(baselines.listByClassifier(PID, "clf-cost"))
                .thenReturn(List.of(
                        baseline("b-wide", wide.toJson(), null),
                        baseline("b-garbage", "{\"kind\":\"histogram\",\"bins\":", null),
                        baseline("b-empty", null, null),
                        baseline("b-flat", sketch(4.6).toJson(), null),
                        // Not pinned yet: its newest closed day is what it reads.
                        baseline("b-middle", null, control(middle.toJson())),
                        baseline("b-tight", tight.toJson(), null)));

        ClassifierDtos.TuningView view = service.getTuning(PID, "clf-cost");

        MetricDriftConfig defaults = MetricDriftConfig.defaults();
        assertTrue(0 < tight.stdDevLog()
                && tight.stdDevLog() < middle.stdDevLog()
                && middle.stdDevLog() < wide.stdDevLog());
        assertEquals(
                3,
                java.util.stream.Stream.of(tight, middle, wide)
                        .map(h -> MetricDriftDetector.impliedFalseAlarmRate(
                                        defaults.w1Floor(), h.stdDevLog(), defaults.windowTargetCount())
                                .getAsDouble())
                        .distinct()
                        .count(),
                "each spread implies its own rate, or this test could not tell them apart");
        assertEquals(
                new ClassifierDtos.TuningView(
                        defaults.windowTargetCount(),
                        defaults.windowMaxHours(),
                        defaults.minSample(),
                        defaults.w1Floor(),
                        MetricDriftDetector.impliedFalseAlarmRate(
                                        defaults.w1Floor(), middle.stdDevLog(), defaults.windowTargetCount())
                                .getAsDouble()),
                view);
    }

    /**
     * Catches a rate printed when nothing has the traffic to support one (it must read as unknown), and the
     * dial being served for a classifier that has no window to tune.
     */
    @Test
    void withNoReadableSpreadTheRateIsUnknownAndOtherDetectorsHaveNoDial() {
        when(signals.findById(PID, "clf-dur"))
                .thenReturn(Optional.of(row("clf-dur", BuiltInDetector.Kind.DURATION_DRIFT, null)));
        when(baselines.listByClassifier(PID, "clf-dur")).thenReturn(List.of(baseline("b-empty", null, null)));
        when(signals.findById(PID, "clf-leak"))
                .thenReturn(Optional.of(row("clf-leak", BuiltInDetector.Kind.SECRET_LEAK, null)));

        assertNull(service.getTuning(PID, "clf-dur").impliedFalseAlarmRate());
        TessaryException e = assertThrows(TessaryException.class, () -> service.getTuning(PID, "clf-leak"));
        assertEquals(ClassifierError.NOT_METRIC_DRIFT, e.error());
    }

    /**
     * Catches a tuning write that replaces the stored config with the four edited fields (dropping which
     * measures the classifier watches and the fields nobody edited), and a response that echoes what was
     * submitted rather than the clamped value actually in effect.
     */
    @Test
    void aTuningWriteMergesIntoTheStoredConfigAndReportsTheClampedValue() throws Exception {
        when(signals.findById(PID, "clf-cost"))
                .thenReturn(Optional.of(row(
                        "clf-cost",
                        BuiltInDetector.Kind.COST_DRIFT,
                        "{\"measures\":[\"cost\"],\"settle_seconds\":120}")));

        ClassifierDtos.TuningView view = service.setTuning(PID, "clf-cost", 2_000, 48, 5, 0.2);

        assertEquals(new ClassifierDtos.TuningView(2_000, 48, 30, 0.2, null), view, "min_sample is clamped to 30");
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(signals).updateConfig(eq(PID), eq("clf-cost"), written.capture());
        JsonNode stored = MAPPER.readTree(written.getValue());
        assertEquals("[\"cost\"]", stored.path("measures").toString());
        assertEquals(120, stored.path("settle_seconds").asInt());
        assertEquals(2_000, stored.path("window_target_count").asInt());
        assertEquals(30, stored.path("min_sample").asInt());
    }

    private static MetricHistogram sketch(double... logValues) {
        MetricHistogram h = new MetricHistogram(MetricHistogram.Grid.duration());
        for (double v : logValues) h.add(v);
        return h;
    }

    /** A control ring holding one closed day whose sketch is {@code sketchJson}. */
    private static String control(String sketchJson) {
        return "{\"kind\":\"control\",\"days\":[{\"d\":\"2026-09-01\",\"m\":" + sketchJson + "}]}";
    }

    private static ClassifierRow row(String id, String detector, @Nullable String configJson) {
        return new ClassifierRow(
                id,
                PID,
                detector,
                detector,
                null,
                detector,
                configJson,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }

    private static MetricBaselineRow baseline(String id, @Nullable String pinnedSketch, @Nullable String controlJson) {
        return new MetricBaselineRow(
                id,
                PID,
                "clf",
                MetricBaselineRow.Measure.COST,
                "call_site",
                "cs-" + id,
                "active",
                pinnedSketch,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                controlJson,
                null,
                0,
                null,
                null,
                null,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }
}

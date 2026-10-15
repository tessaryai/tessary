// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.finding.FindingRepository.ConfirmedSpan;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The reference a drift bucket is judged against, read the same way {@link MetricDriftSweep} reads it: the pinned
 * sketch while on the live grid, else the rolling control.
 */
public final class MetricBaselineReference {

    private MetricBaselineReference() {}

    /** In the measure's own unit (ms or USD). */
    public record Quantiles(double p50, double p95) {}

    /** Empty when neither has a sketch on {@code grid}. */
    public static Optional<Quantiles> quantiles(
            MetricBaselineRow row, Grid grid, String eventDay, Set<String> excludedDays) {
        MetricReading reference = pinnedOnGrid(row.pinnedSketchJson(), grid);
        if (reference == null) {
            MetricControl.Resolved control =
                    MetricControl.fromJson(row.controlJson()).resolve(grid, eventDay, excludedDays);
            reference = control == null ? null : control.measure();
        }
        if (reference == null) return Optional.empty();
        OptionalDouble p50 = MetricFindingEvidence.rawQuantile(reference, 0.5);
        OptionalDouble p95 = MetricFindingEvidence.rawQuantile(reference, 0.95);
        if (p50.isEmpty() || p95.isEmpty()) return Optional.empty();
        return Optional.of(new Quantiles(p50.getAsDouble(), p95.getAsDouble()));
    }

    private static @Nullable MetricSketch pinnedOnGrid(@Nullable String json, Grid grid) {
        if (json == null || json.isBlank()) return null;
        try {
            MetricSketch sketch = MetricSketch.fromJson(json);
            return sketch.gridId().equals(grid.id()) ? sketch : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Every day a confirmed regression touched, not just its first: a week-long regression must not become its own bar.
     */
    public static Set<String> excludedDays(@Nullable List<ConfirmedSpan> spans) {
        if (spans == null || spans.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (ConfirmedSpan span : spans) {
            LocalDate from = LocalDate.ofInstant(Instant.parse(span.fromAt()), ZoneOffset.UTC);
            LocalDate to = LocalDate.ofInstant(Instant.parse(span.toAt()), ZoneOffset.UTC);
            if (to.isBefore(from)) continue;
            // Bounded by the ring's retention, so a year-long spell doesn't walk a year of dates.
            LocalDate floor = to.minusDays(MetricControl.RETAIN_DAYS);
            for (LocalDate d = from.isBefore(floor) ? floor : from; !d.isAfter(to); d = d.plusDays(1)) {
                out.add(d.toString());
            }
        }
        return out;
    }
}

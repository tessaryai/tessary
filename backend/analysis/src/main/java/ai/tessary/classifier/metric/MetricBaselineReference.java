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
 * The reference a drift bucket is judged against, as a reader outside the sweep sees it: the pinned sketch while it is
 * on the live grid, else the rolling control resolved for a day. The same two references {@link MetricDriftSweep}
 * compares a closing window against, read the same way.
 */
public final class MetricBaselineReference {

    private MetricBaselineReference() {}

    /** A reference's median and 95th percentile, in the measure's own unit (ms or USD). */
    public record Quantiles(double p50, double p95) {}

    /**
     * {@code row}'s reference on {@code grid}, judged on {@code eventDay}: the pinned sketch when it is on that grid,
     * else the control resolved without {@code excludedDays}. Empty when neither has a sketch on the grid.
     */
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
     * The UTC days a confirmed regression on this bucket ran through — the days the rolling control must
     * leave out.
     *
     * <p>Every day the spell touched, not just the day it opened. A regression that ran for a week was
     * not normal on any of those days, and excluding only its first would let the rest of it become the
     * bar it is being measured against.
     */
    public static Set<String> excludedDays(@Nullable List<ConfirmedSpan> spans) {
        if (spans == null || spans.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (ConfirmedSpan span : spans) {
            LocalDate from = LocalDate.ofInstant(Instant.parse(span.fromAt()), ZoneOffset.UTC);
            LocalDate to = LocalDate.ofInstant(Instant.parse(span.toAt()), ZoneOffset.UTC);
            if (to.isBefore(from)) continue;
            // Bounded by the ring's own retention: a spell running for a year would otherwise walk a year
            // of dates to exclude days the control stopped holding weeks ago.
            LocalDate floor = to.minusDays(MetricControl.RETAIN_DAYS);
            for (LocalDate d = from.isBefore(floor) ? floor : from; !d.isAfter(to); d = d.plusDays(1)) {
                out.add(d.toString());
            }
        }
        return out;
    }
}

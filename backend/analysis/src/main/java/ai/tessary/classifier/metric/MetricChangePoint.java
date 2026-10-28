// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.finding.FindingEvidenceRepository.Ref;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricEvidenceRefs.Timed;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Where inside a shifted window the shift began: a one-sided CUSUM of log values against the reference mean, with no
 * slack. The onset is the step after the latest minimiser, so a window shifted throughout reports its first row. Pure,
 * so the same rows give the same onset on every run.
 */
final class MetricChangePoint {

    /** How many rows on each side of the change point are written as evidence. */
    static final int ROWS_AROUND = 10;

    /**
     * A reference mean over identical rows is a float sum a few ulps off the row value. CUSUM values this close are a
     * tie, or that rounding would move the onset of a flat-then-step window to its first row.
     */
    private static final double TIE_LOG = 1e-9;

    private static final Comparator<Timed> EVENT_ORDER = Comparator.comparing(
                    (Timed t) -> Instant.parse(Objects.requireNonNull(t.at())))
            .thenComparing(t -> t.ref().traceId(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(t -> t.ref().spanId(), Comparator.nullsFirst(Comparator.naturalOrder()));

    private MetricChangePoint() {}

    record Located(int index, String at) {}

    /** Instants, never text: {@code Instant.toString} drops a zero fraction, so lexical order is not event order. */
    static List<Timed> ordered(List<Timed> window) {
        return window.stream().sorted(EVENT_ORDER).toList();
    }

    /** Null when any row lacks a time or a value: a window spanning older blobs has no located change point. */
    static @Nullable Located locate(List<Timed> window, double referenceMeanLog, Direction direction) {
        if (window.isEmpty() || !window.stream().allMatch(Timed::valued)) return null;
        List<Timed> ordered = ordered(window);
        double sign = direction == Direction.DOWN ? -1 : 1;
        int n = ordered.size();
        double[] cusum = new double[n];
        double min = 0;
        for (int k = 1; k < n; k++) {
            double value = Objects.requireNonNull(ordered.get(k - 1).value());
            cusum[k] = cusum[k - 1] + sign * (value - referenceMeanLog);
            min = Math.min(min, cusum[k]);
        }
        int index = 0;
        for (int k = 0; k < n; k++) {
            if (cusum[k] <= min + TIE_LOG) index = k;
        }
        return new Located(index, Objects.requireNonNull(ordered.get(index).at()));
    }

    /** The rows at and after the change point, in event order. */
    static List<Ref> from(List<Timed> ordered, int index, int max) {
        return MetricEvidenceRefs.refs(ordered.subList(index, Math.min(ordered.size(), index + max)));
    }

    /** The rows just before the change point, in event order. */
    static List<Ref> before(List<Timed> ordered, int index, int max) {
        return MetricEvidenceRefs.refs(ordered.subList(Math.max(0, index - max), index));
    }
}

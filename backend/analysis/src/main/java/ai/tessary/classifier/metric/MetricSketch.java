// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A bounded, mergeable summary of {@code log(value)} samples for one (bucket × measure) window. An implementation must
 * not grow with the sample count: a thin bucket's window stays open up to a week. Everything is in log space so
 * {@code e^W₁} is the multiplicative shift a finding reports. Implementations are mutable; use {@link #copy()} for
 * snapshots.
 */
public interface MetricSketch extends MetricReading {

    /**
     * Fold one {@code ln} sample in. Out-of-grid values count at the edge, never dropped; {@code -inf} lands in
     * underflow, {@code +inf} in overflow, and {@code NaN} throws.
     */
    void add(double logValue);

    /**
     * Fold another sketch in place; exact, since merging is bin-wise addition. Throws if {@code other} is on a
     * different grid.
     */
    void merge(MetricSketch other);

    MetricSketch copy();

    /**
     * Standard deviation in log space. Zero for an empty, single-sample or single-bin sketch: read that as "no width
     * measured", not as a bar of zero.
     */
    double stdDevLog();

    String toJson();

    /**
     * Dispatches on the payload's {@code kind} so a later implementation can coexist with stored histograms without a
     * backfill. @throws IllegalArgumentException on malformed JSON or an unknown {@code kind}
     */
    static MetricSketch fromJson(String json) {
        String kind = kindOf(json);
        return switch (kind) {
            case MetricHistogram.KIND -> MetricHistogram.fromJson(json);
            default -> throw new IllegalArgumentException("unknown metric sketch kind: " + kind);
        };
    }

    private static String kindOf(String json) {
        try {
            JsonNode node = MetricHistogram.JSON.readTree(json);
            JsonNode kind = node.get("kind");
            if (kind == null || !kind.isTextual()) {
                throw new IllegalArgumentException("metric sketch json has no 'kind' discriminator");
            }
            return kind.asText();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric sketch json", e);
        }
    }
}

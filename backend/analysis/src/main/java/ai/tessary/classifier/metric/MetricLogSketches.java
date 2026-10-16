// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.util.List;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * One sketch per named quantity on a shared grid, persisted as a JSON object keyed by name: what {@link MetricTokens}
 * and {@link MetricWorkload} have in common, which is everything but their quantities and why each is persisted.
 */
abstract sealed class MetricLogSketches permits MetricTokens, MetricWorkload {

    public static Grid grid(int bins) {
        return new Grid(1.0, MetricHistogram.DEFAULT_RATIO, bins);
    }

    final Grid grid;

    private final List<String> quantities;

    private final MetricSketch[] sketches;

    MetricLogSketches(List<String> quantities, Grid grid) {
        this.quantities = quantities;
        this.grid = grid;
        this.sketches = new MetricSketch[quantities.size()];
        for (int i = 0; i < sketches.length; i++) {
            sketches[i] = new MetricHistogram(grid);
        }
    }

    /** Stored as {@code log1p}, so a zero lands on the grid; a null or non-finite reading contributes nothing. */
    final void addOne(int index, @Nullable Double raw) {
        if (raw == null || !Double.isFinite(raw)) return;
        sketches[index].add(Math.log1p(Math.max(0.0, raw)));
    }

    final void mergeSketches(MetricLogSketches other) {
        for (int i = 0; i < sketches.length; i++) {
            sketches[i].merge(other.sketches[i]);
        }
    }

    public boolean isEmpty() {
        for (MetricSketch sketch : sketches) {
            if (sketch.count() > 0) return false;
        }
        return true;
    }

    public OptionalDouble p50(String quantity) {
        int i = quantities.indexOf(quantity);
        if (i < 0 || sketches[i].count() == 0) return OptionalDouble.empty();
        Double q = sketches[i].quantile(0.5);
        return q == null ? OptionalDouble.empty() : OptionalDouble.of(Math.expm1(q));
    }

    public String toJson() {
        ObjectNode root = MetricHistogram.JSON.createObjectNode();
        for (int i = 0; i < sketches.length; i++) {
            root.putRawValue(quantities.get(i), new RawValue(sketches[i].toJson()));
        }
        return root.toString();
    }

    /** A quantity missing, or on a different grid than this one's, is left empty. */
    final void readJson(String json, String what) {
        JsonNode node;
        try {
            node = MetricHistogram.JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed metric " + what + " json", e);
        }
        for (int i = 0; i < quantities.size(); i++) {
            JsonNode child = node.get(quantities.get(i));
            if (child == null || child.isNull()) continue;
            MetricSketch sketch = MetricSketch.fromJson(child.toString());
            if (sketch.gridId().equals(grid.id())) sketches[i] = sketch;
        }
    }
}

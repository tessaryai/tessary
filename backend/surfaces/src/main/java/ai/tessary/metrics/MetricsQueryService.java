// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.metrics.MetricsMeasure.GapFill;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalog;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogDimension;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogLimits;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogMeasure;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalogView;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryResult;
import ai.tessary.metrics.MetricsQueryDtos.MetricsRow;
import ai.tessary.metrics.MetricsQueryDtos.MetricsTopN;
import ai.tessary.metrics.MetricsQueryDtos.MetricsWindow;
import ai.tessary.metrics.MetricsQueryRepository.Raw;
import ai.tessary.metrics.MetricsQueryRepository.Result;
import ai.tessary.metrics.MetricsStatement.Shape;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Validates, plans and runs one metrics query, then shapes the rows for the wire: gap-filled buckets, every key in
 * every bucket in rank order, labels, and the top N block.
 */
@Service
public class MetricsQueryService {

    private static final Logger log = LoggerFactory.getLogger(MetricsQueryService.class);

    private static final MetricsCatalog CATALOG = new MetricsCatalog(
            Arrays.stream(MetricsView.values())
                    .map(v -> new MetricsCatalogView(
                            v.wire(),
                            v.label(),
                            v.description(),
                            v.dimensions().stream()
                                    .map(d -> new MetricsCatalogDimension(d.wire(), d.label(), d.highCardinality()))
                                    .toList(),
                            v.measures().stream()
                                    .map(m -> new MetricsCatalogMeasure(
                                            m.wire(),
                                            m.label(),
                                            m.unit().wire(),
                                            m.gapFill().wire(),
                                            m.companions()))
                                    .toList()))
                    .toList(),
            Arrays.stream(MetricsGranularity.values())
                    .map(MetricsGranularity::wire)
                    .toList(),
            new MetricsCatalogLimits(
                    MetricsQueryValidator.MAX_WINDOW_DAYS,
                    MetricsQueryValidator.MAX_HOUR_WINDOW_DAYS,
                    MetricsQueryValidator.MAX_TOP_N_WITH_AXIS,
                    MetricsQueryValidator.MAX_TOP_N,
                    MetricsQueryValidator.MAX_MEASURES,
                    MetricsQueryValidator.MAX_FILTERS,
                    MetricsQueryValidator.MAX_FILTER_VALUES,
                    MetricsQueryValidator.MAX_WIDGETS,
                    MetricsQueryValidator.MAX_DASHBOARDS));

    private final MetricsQueryRepository repo;
    private final MetricsLabelRepository labels;
    private final MetricsGate gate;
    private final MetricsProperties props;

    public MetricsQueryService(
            MetricsQueryRepository repo, MetricsLabelRepository labels, MetricsGate gate, MetricsProperties props) {
        this.repo = repo;
        this.labels = labels;
        this.gate = gate;
        this.props = props;
    }

    public MetricsCatalog catalog() {
        return CATALOG;
    }

    public MetricsQueryResult query(String projectId, MetricsQueryRequest req) {
        MetricsQuery q = MetricsQueryValidator.resolve(req);
        Instant start = Instant.now();
        MetricsStatement current = MetricsQueryPlanner.plan(q, projectId, q.from(), q.to());
        MetricsStatement previous =
                q.compare() ? MetricsQueryPlanner.plan(q, projectId, q.from().minus(q.length()), q.from()) : null;
        Result raw;
        try {
            raw = gate.withPermit(() -> repo.run(current, previous, props.statementTimeoutMs()));
        } catch (DataAccessException e) {
            RuntimeException translated = MetricsFailures.translate(e);
            logQuery(q, start, 0, translated instanceof TessaryException);
            throw translated;
        }

        Map<String, String> names = Map.of();
        MetricsDimension dimension = q.dimension();
        List<Raw> previousRaw = raw.previousRows();
        if (dimension != null && "call_site".equals(dimension.wire())) {
            Set<String> keys = new HashSet<>();
            for (Raw r : raw.rows()) keys.add(Objects.requireNonNull(r.key()));
            if (previousRaw != null) for (Raw r : previousRaw) keys.add(Objects.requireNonNull(r.key()));
            names = labels.callSiteLabels(projectId, keys);
        }

        MetricsGranularity unit = q.unit();
        List<Instant> buckets = unit == null ? List.of() : unit.buckets(q.from(), q.to());
        List<MetricsRow> rows = assemble(q, current.shape(), raw.rows(), buckets, names);
        List<MetricsRow> previousRows =
                previousRaw == null ? null : assemble(q, current.shape(), previousRaw, buckets, names);

        long totalKeys = raw.rows().isEmpty() ? 0 : raw.rows().get(0).totalKeys();
        MetricsTopN topN = q.hasDimension() ? new MetricsTopN((int) Math.min(q.topN(), totalKeys), totalKeys) : null;
        MetricsQueryResult result = new MetricsQueryResult(
                q.view().wire(),
                new MetricsWindow(
                        q.from().toString(), q.to().toString(), unit == null ? null : unit.wire(), buckets.size()),
                dimension == null ? null : dimension.wire(),
                rows,
                previousRows,
                topN,
                totalKeys > q.topN(),
                Duration.between(start, Instant.now()).toMillis());
        logQuery(q, start, rows.size(), false);
        return result;
    }

    /** Previous rows have no axis (compare refuses one), so {@code buckets} only ever serves the current rows. */
    private static List<MetricsRow> assemble(
            MetricsQuery q, Shape shape, List<Raw> raws, List<Instant> buckets, Map<String, String> names) {
        long totalKeys = raws.isEmpty() ? 0 : raws.get(0).totalKeys();
        if (!shape.hasAxis()) {
            return raws.stream()
                    .map(r -> row(q, null, r.key(), label(r.key(), totalKeys, q.topN(), names), r.n(), r.values()))
                    .toList();
        }
        List<@Nullable String> keys = shape.hasDimension()
                ? raws.stream()
                        .sorted(Comparator.comparingLong(Raw::rank))
                        .map(Raw::key)
                        .distinct()
                        .toList()
                : Collections.singletonList(null);
        Map<BucketKey, Raw> present = new HashMap<>();
        for (Raw r : raws) present.put(new BucketKey(Objects.requireNonNull(r.bucket()), r.key()), r);
        List<MetricsRow> out = new ArrayList<>();
        for (Instant bucket : buckets) {
            for (String key : keys) {
                Raw r = present.get(new BucketKey(bucket, key));
                String label = label(key, totalKeys, q.topN(), names);
                out.add(row(q, bucket, key, label, r == null ? 0 : r.n(), r == null ? Map.of() : r.values()));
            }
        }
        return out;
    }

    private record BucketKey(Instant bucket, @Nullable String key) {}

    /**
     * Values in the order of the query's measures. A measure SQL returned as NULL, and one a filled bucket lacks, take
     * the measure's gap fill: a sum over rows none of which pass its filter is 0, exactly as an empty bucket is, and
     * its companion says why.
     */
    private static MetricsRow row(
            MetricsQuery q,
            @Nullable Instant bucket,
            @Nullable String key,
            @Nullable String label,
            long n,
            Map<String, @Nullable Double> raw) {
        Map<String, @Nullable Double> values = new LinkedHashMap<>();
        for (MetricsMeasure m : q.all()) {
            Double v = raw.get(m.wire());
            values.put(m.wire(), v == null && m.gapFill() == GapFill.ZERO ? Double.valueOf(0.0) : v);
        }
        return new MetricsRow(bucket == null ? null : bucket.toString(), key, label, n, values);
    }

    private static @Nullable String label(@Nullable String key, long totalKeys, int topN, Map<String, String> names) {
        if (key == null) return null;
        if (MetricsSql.UNATTRIBUTED.equals(key)) return "Unattributed";
        if (MetricsSql.OTHER.equals(key)) return "Other (" + (totalKeys - topN) + " more)";
        return names.getOrDefault(key, key);
    }

    /** No filter values and no keys: the OPS marker forbids user-supplied fields. */
    private static void logQuery(MetricsQuery q, Instant start, int rows, boolean timedOut) {
        MetricsGranularity unit = q.unit();
        MetricsDimension dimension = q.dimension();
        StructuredLog.info(log, Markers.OPS, "metrics.query")
                .field("view", q.view().wire())
                .field("windowHours", q.length().toHours())
                .field("granularity", unit == null ? "none" : unit.wire())
                .field("dimension", dimension == null ? "none" : dimension.wire())
                .field("rows", rows)
                .field("timedOut", timedOut)
                .durationMs(start)
                .log();
    }
}

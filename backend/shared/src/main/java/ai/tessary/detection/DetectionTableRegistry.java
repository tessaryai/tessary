// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Every {@link DetectionTable} on the classpath, indexed by the detector kind it claims, the same
 * {@code ObjectProvider} + {@code toUnmodifiableMap} idiom {@code ClassifierSweepRegistry} (backend/
 * analysis) already uses for sweep dispatch, copied here rather than reused because that class lives
 * in a module {@code backend/shared} cannot depend on.
 *
 * <p>{@link #unionSql()} builds the same shape a hand-written {@code UNION ALL} view would, at
 * query time, from whatever tables are actually registered on this classpath: a table that isn't
 * present just drops its arm instead of the query referencing a relation that was never created.
 *
 * <p>Two beans registering the same {@code detectorKind} fail the context at boot
 * ({@code toUnmodifiableMap} throws {@code IllegalStateException} on a duplicate key), the same
 * "loud, not silently-last-wins" contract {@code ClassifierSweepRegistry} chose, for the same reason:
 * which of two conflicting detection tables "wins" must never depend on classpath order.
 */
@Component
public class DetectionTableRegistry {

    private final Map<String, DetectionTable> byKind;

    public DetectionTableRegistry(ObjectProvider<DetectionTable> tables) {
        this.byKind = tables.orderedStream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(DetectionTable::detectorKind, t -> t));
    }

    /** The table for {@code detectorKind}, or null when that classifier writes no per-span detection. */
    public @Nullable String tableFor(String detectorKind) {
        DetectionTable t = byKind.get(detectorKind);
        return t == null ? null : t.table();
    }

    /** True when {@code detectorKind} has a registered detection table. */
    public boolean writesDetections(String detectorKind) {
        return byKind.containsKey(detectorKind);
    }

    /**
     * The distinct {@link DetectionTable}s registered, deduplicated by table name (two kinds, e.g.
     * {@code CLASSIFIER} and {@code REGEX}, legitimately share one table) and sorted by table name
     * for determinism. {@code RetentionRepository}'s sweep iterates this list; nothing should rely on
     * incidental Spring bean-registration order, which is not guaranteed stable.
     */
    public List<DetectionTable> tables() {
        Map<String, DetectionTable> byTable = new LinkedHashMap<>();
        for (DetectionTable t : byKind.values()) {
            byTable.putIfAbsent(t.table(), t);
        }
        return byTable.values().stream()
                .sorted(Comparator.comparing(DetectionTable::table))
                .toList();
    }

    /**
     * The stitched union over every registered table, twelve columns per arm. Callers wrap this
     * in parentheses with an alias, {@code "(" + unionSql() + ") d"}, the same way a view name
     * would be referenced.
     */
    public String unionSql() {
        return tables().stream()
                .map(this::arm)
                .reduce((a, b) -> a + " UNION ALL " + b)
                .orElse("SELECT NULL"
                        + " WHERE false"); // no registered tables (a degenerate classpath): an empty, well-typed
        // relation
    }

    private String arm(DetectionTable t) {
        String table = t.table();
        return "SELECT " + table + ".id, " + table + ".project_id, " + table
                + ".classifier_key AS classifier_id, " + table + ".severity, " + table + ".confidence, '"
                + t.grain().subjectKind() + "'::text AS subject_kind, " + table + ".subject_session_id, "
                + table + ".subject_trace_id, " + table + ".subject_span_id, " + table + ".evidence, "
                + table + ".project_version_id, " + table + ".created_at FROM " + table;
    }
}

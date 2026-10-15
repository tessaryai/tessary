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
 * Every {@link DetectionTable} on the classpath by detector kind ({@code ClassifierSweepRegistry}'s idiom, copied since
 * that module is out of reach). {@link #unionSql()} builds the union at query time from the registered tables only. A
 * duplicate kind fails boot rather than letting classpath order pick a winner.
 */
@Component
public class DetectionTableRegistry {

    private final Map<String, DetectionTable> byKind;

    public DetectionTableRegistry(ObjectProvider<DetectionTable> tables) {
        this.byKind = tables.orderedStream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(DetectionTable::detectorKind, t -> t));
    }

    /** Null when that classifier writes no per-span detection. */
    public @Nullable String tableFor(String detectorKind) {
        DetectionTable t = byKind.get(detectorKind);
        return t == null ? null : t.table();
    }

    public boolean writesDetections(String detectorKind) {
        return byKind.containsKey(detectorKind);
    }

    /** Deduplicated by table name (kinds may share a table) and sorted, since bean registration order is not stable. */
    public List<DetectionTable> tables() {
        Map<String, DetectionTable> byTable = new LinkedHashMap<>();
        for (DetectionTable t : byKind.values()) {
            byTable.putIfAbsent(t.table(), t);
        }
        return byTable.values().stream()
                .sorted(Comparator.comparing(DetectionTable::table))
                .toList();
    }

    /** Not deduplicated: each kind's flags are told apart by its classifier rows' {@code detector}. */
    public List<DetectionTable> shownInTraceViews() {
        return byKind.values().stream()
                .filter(DetectionTable::shownInTraceViews)
                .sorted(Comparator.comparing(DetectionTable::table).thenComparing(DetectionTable::detectorKind))
                .toList();
    }

    public boolean showsInTraceViews(String detectorKind) {
        DetectionTable t = byKind.get(detectorKind);
        return t != null && t.shownInTraceViews();
    }

    /** Thirteen columns per arm. Callers wrap it as {@code "(" + unionSql() + ") d"}. */
    public String unionSql() {
        return tables().stream()
                .map(this::arm)
                .reduce((a, b) -> a + " UNION ALL " + b)
                .orElseThrow();
    }

    private String arm(DetectionTable t) {
        String table = t.table();
        return "SELECT " + table + ".id, " + table + ".project_id, " + table
                + ".classifier_key AS classifier_id, " + table + ".severity, " + table + ".confidence, '"
                + t.grain().subjectKind() + "'::text AS subject_kind, " + table + ".subject_session_id, "
                + table + ".subject_trace_id, " + table + ".subject_span_id, " + table + ".evidence, "
                + table + ".project_version_id, " + table + ".created_at, " + table
                + ".subject_started_at FROM " + table;
    }
}

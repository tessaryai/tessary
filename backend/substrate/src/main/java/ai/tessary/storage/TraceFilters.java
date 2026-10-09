// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The trace list's filters as SQL over a {@code trace t}, shared by the traces list and the sessions list so
 * that a session matches exactly when one of its traces would show in the traces list.
 */
@Component
public class TraceFilters {

    private final TraceDetectionRepository detections;

    public TraceFilters(TraceDetectionRepository detections) {
        this.detections = detections;
    }

    /** True when the query narrows the traces at all. */
    public static boolean narrows(TraceV2Repository.TraceQuery query) {
        return present(query.model())
                || present(query.kind())
                || present(query.callSite())
                || query.hasCallSite() != null
                || present(query.from())
                || present(query.to())
                || present(query.status())
                || present(query.q())
                || present(query.detectedBy());
    }

    /**
     * Appends one {@code AND} clause per filter the query sets, binding its parameters into {@code params}.
     *
     * <p>{@code status} reads the rollup: {@code error} means {@code error_count > 0}, {@code ok} means it is zero.
     * A trace that has never rolled up has a null {@code error_count} and is therefore neither; it is excluded by an
     * explicit status filter rather than silently counted as healthy.
     */
    public void append(
            String projectId, StringBuilder where, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        addEq(where, params, " AND t.started_at >= :fromTs::timestamptz", "fromTs", query.from());
        addEq(where, params, " AND t.started_at <= :toTs::timestamptz", "toTs", query.to());

        addExists(where, params, "provided_model_name", "model", query.model());
        addExists(where, params, "kind", "kind", query.kind());
        addExists(where, params, "call_site_id", "callSite", query.callSite());
        Boolean hasCallSite = query.hasCallSite();
        if (hasCallSite != null) {
            where.append(hasCallSite ? " AND EXISTS" : " AND NOT EXISTS")
                    .append(" (SELECT 1 FROM span sx WHERE sx.project_id = t.project_id"
                            + " AND sx.trace_id = t.id AND NOT sx.is_deleted AND sx.call_site_id IS NOT NULL)");
        }

        String status = query.status();
        if (present(status)) {
            where.append(" AND t.error_count IS NOT NULL AND t.error_count ")
                    .append("error".equalsIgnoreCase(status) ? "> 0" : "= 0");
        }

        String q = query.q();
        if (present(q)) {
            where.append(" AND (t.name ILIKE :q OR t.session_id ILIKE :q OR t.thread_id ILIKE :q"
                    + " OR t.user_id ILIKE :q OR t.id ILIKE :q)");
            params.put("q", "%" + q + "%");
        }

        String detectedBy = query.detectedBy();
        if (present(detectedBy)) {
            detections.appendFilter(projectId, where, params, detectedBy);
        }
    }

    /**
     * {@code EXISTS (SELECT 1 FROM span …)} on one span column, a filter, never an aggregation.
     *
     * <p>The correlated predicate carries {@code project_id} as well as {@code trace_id} because the span primary key
     * leads with the project: without it the subquery would scan by trace id alone, which is both slower and one typo
     * away from crossing a project boundary.
     */
    private static void addExists(
            StringBuilder where, Map<String, Object> params, String column, String name, @Nullable String value) {
        if (!present(value)) {
            return;
        }
        where.append(" AND EXISTS (SELECT 1 FROM span sx WHERE sx.project_id = t.project_id"
                + " AND sx.trace_id = t.id AND NOT sx.is_deleted AND sx." + column + " = :" + name + ")");
        params.put(name, value);
    }

    private static void addEq(
            StringBuilder clause, Map<String, Object> params, String fragment, String name, @Nullable String value) {
        if (!present(value)) {
            return;
        }
        clause.append(fragment);
        params.put(name, value);
    }

    private static boolean present(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.detection.DetectionTableRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which classifiers flagged a trace, for the trace and session views: the marks on a row, and the
 * {@code detected_by} filter.
 *
 * <p>A classifier's flags live in its own detection table ({@link DetectionTableRegistry}), keyed by the
 * classifier row's {@code detector}. Only the detectors in {@link #CLEARABLE_BY_DETECTOR} show here; the others
 * join that map when the views take them on. A flag with {@code cleared_at} set was resolved as a false alarm and
 * is not a flag.
 *
 * <p>Every read starts from the project's {@code classifier} rows, a handful, and probes the detection table's
 * unique {@code (project_id, classifier_id, subject_trace_id, …)} index from there, so it is bounded by the traces
 * it is asked about and never scans a detection table.
 */
@Repository
public class TraceDetectionRepository {

    /** The {@code detected_by} value that matches a detection by any classifier. */
    public static final String ANY = "any";

    /** The detectors these views show, each with whether its table carries {@code cleared_at}. */
    private static final Map<String, Boolean> CLEARABLE_BY_DETECTOR = Map.of("frustration", true);

    /** One classifier's flag on one trace, at the span it judged. */
    public record Mark(String traceId, @Nullable String spanId, String classifierId, String classifierName) {}

    /** A classifier that flagged one of a session's traces, for the sessions list. */
    public record SessionMark(String sessionId, String classifierId, String classifierName) {}

    private record Source(String detector, String table, boolean clearable) {}

    private final JdbcClient jdbc;
    private final List<Source> sources;

    public TraceDetectionRepository(JdbcClient jdbc, DetectionTableRegistry registry) {
        this.jdbc = jdbc;
        List<Source> found = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : CLEARABLE_BY_DETECTOR.entrySet()) {
            String table = registry.tableFor(e.getKey());
            if (table != null) {
                found.add(new Source(e.getKey(), table, e.getValue()));
            }
        }
        found.sort(Comparator.comparing(Source::detector));
        this.sources = List.copyOf(found);
    }

    /** Every uncleared flag on these traces, by classifier name. */
    public List<Mark> forTraces(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty() || sources.isEmpty()) {
            return List.of();
        }
        String sql = sources.stream()
                .map(s -> "SELECT d.subject_trace_id, d.subject_span_id, c.id AS classifier_id, c.name"
                        + " FROM classifier c JOIN " + s.table() + " d"
                        + " ON d.project_id = c.project_id AND d.classifier_id = c.id"
                        + " WHERE c.project_id = :pid AND c.detector = '" + s.detector() + "'"
                        + " AND d.subject_trace_id IN (:traceIds)" + uncleared(s))
                .collect(Collectors.joining(" UNION ALL "));
        return jdbc.sql(sql + " ORDER BY name, subject_trace_id, subject_span_id")
                .param("pid", projectId)
                .param("traceIds", traceIds)
                .query((rs, n) -> new Mark(
                        rs.getString("subject_trace_id"),
                        rs.getString("subject_span_id"),
                        rs.getString("classifier_id"),
                        rs.getString("name")))
                .list();
    }

    /** The classifiers that flagged any trace of these sessions, one row per session and classifier. */
    public List<SessionMark> forSessions(String projectId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty() || sources.isEmpty()) {
            return List.of();
        }
        String sql = sources.stream()
                .map(s -> "SELECT DISTINCT t.session_id, c.id AS classifier_id, c.name"
                        + " FROM trace t JOIN classifier c ON c.project_id = t.project_id"
                        + " AND c.detector = '" + s.detector() + "'"
                        + " JOIN " + s.table() + " d ON d.project_id = c.project_id AND d.classifier_id = c.id"
                        + " AND d.subject_trace_id = t.id" + uncleared(s)
                        + " WHERE t.project_id = :pid AND t.session_id IN (:sessionIds) AND NOT t.is_deleted")
                .collect(Collectors.joining(" UNION "));
        return jdbc.sql(sql + " ORDER BY name, session_id")
                .param("pid", projectId)
                .param("sessionIds", sessionIds)
                .query((rs, n) -> new SessionMark(
                        rs.getString("session_id"), rs.getString("classifier_id"), rs.getString("name")))
                .list();
    }

    /**
     * Appends {@code AND} a clause over {@code trace t} that keeps a trace {@code detectedBy} flagged: a classifier
     * id, or {@link #ANY}. A classifier these views do not show matches nothing.
     *
     * <p>The classifier ids are looked up first and bound as values, never joined in the clause. Bound, the planner
     * reads the detection table's statistics for that classifier and picks the plan that fits its flag density;
     * joined through {@code classifier}, it cannot, and on a dense project it hashed every flag before returning a
     * page (887 ms against 43 ms over 2,000,000 traces, {@code TraceDetectionQueryPlanIT}).
     */
    public void appendFilter(String projectId, StringBuilder where, Map<String, Object> params, String detectedBy) {
        boolean any = ANY.equals(detectedBy);
        Map<String, List<String>> idsByDetector = new HashMap<>();
        if (!sources.isEmpty()) {
            var lookup = jdbc.sql(
                            "SELECT id, detector FROM classifier WHERE project_id = :pid AND detector IN (:detectors)"
                                    + (any ? "" : " AND id = :id"))
                    .param("pid", projectId)
                    .param("detectors", sources.stream().map(Source::detector).toList());
            if (!any) {
                lookup = lookup.param("id", detectedBy);
            }
            lookup.query((rs, n) -> Map.entry(rs.getString("detector"), rs.getString("id")))
                    .list()
                    .forEach(e -> idsByDetector
                            .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add(e.getValue()));
        }
        List<String> arms = new ArrayList<>();
        for (Source s : sources) {
            List<String> ids = idsByDetector.get(s.detector());
            if (ids == null) {
                continue;
            }
            String name = "detectedBy" + arms.size();
            params.put(name, ids);
            arms.add("EXISTS (SELECT 1 FROM " + s.table() + " d WHERE d.project_id = t.project_id"
                    + " AND d.classifier_id IN (:" + name + ") AND d.subject_trace_id = t.id" + uncleared(s) + ")");
        }
        where.append(" AND (")
                .append(arms.isEmpty() ? "FALSE" : String.join(" OR ", arms))
                .append(')');
    }

    private static String uncleared(Source s) {
        return s.clearable() ? " AND d.cleared_at IS NULL" : "";
    }
}

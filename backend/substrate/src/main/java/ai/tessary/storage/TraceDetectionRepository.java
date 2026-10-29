// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTableRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which classifiers flagged a trace, for the trace and session views. Only tables registered as
 * {@link DetectionTableRegistry#shownInTraceViews()} count, and a cleared flag is not a flag. Every read starts from
 * the project's few classifier rows and probes each detection table's unique index, never scanning.
 */
@Repository
public class TraceDetectionRepository {

    public static final String ANY = "any";

    /** @param grain what the classifier judged: a whole turn ({@code trace}) or one span's output ({@code span}) */
    public record Mark(
            String traceId,
            @Nullable String spanId,
            String classifierId,
            String classifierName,
            DetectionTable.Grain grain) {}

    public record SessionMark(String sessionId, String classifierId, String classifierName) {}

    private final JdbcClient jdbc;
    private final List<DetectionTable> sources;

    public TraceDetectionRepository(JdbcClient jdbc, DetectionTableRegistry registry) {
        this.jdbc = jdbc;
        this.sources = registry.shownInTraceViews();
    }

    public List<Mark> forTraces(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty() || sources.isEmpty()) {
            return List.of();
        }
        String sql = sources.stream()
                .map(s -> "SELECT d.subject_trace_id, d.subject_span_id, c.id AS classifier_id, c.name, '"
                        + s.grain().name() + "' AS grain FROM classifier c JOIN " + s.table() + " d"
                        + " ON d.project_id = c.project_id AND d.classifier_id = c.id"
                        + " WHERE c.project_id = :pid AND c.detector = '" + s.detectorKind() + "'"
                        + " AND d.subject_trace_id IN (:traceIds)" + uncleared(s))
                .collect(Collectors.joining(" UNION ALL "));
        return jdbc.sql(sql + " ORDER BY name, subject_trace_id, subject_span_id")
                .param("pid", projectId)
                .param("traceIds", traceIds)
                .query((rs, n) -> new Mark(
                        rs.getString("subject_trace_id"),
                        rs.getString("subject_span_id"),
                        rs.getString("classifier_id"),
                        rs.getString("name"),
                        DetectionTable.Grain.valueOf(rs.getString("grain"))))
                .list();
    }

    public List<SessionMark> forSessions(String projectId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty() || sources.isEmpty()) {
            return List.of();
        }
        String sql = sources.stream()
                .map(s -> "SELECT DISTINCT t.session_id, c.id AS classifier_id, c.name"
                        + " FROM trace t JOIN classifier c ON c.project_id = t.project_id"
                        + " AND c.detector = '" + s.detectorKind() + "'"
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
     * One {@code EXISTS} over {@code t} per picked classifier, or a single one for {@link #ANY}; joined with AND a trace
     * has every pick, with OR any of them. An arm per id rather than per table, since classifiers share a table. A pick
     * the lookup does not find is a {@code FALSE} arm. Ids are looked up first and bound, never joined: bound, the
     * planner uses that classifier's statistics (887 ms vs 43 ms over 2M traces).
     */
    public List<String> arms(String projectId, Map<String, Object> params, List<String> detectedBy) {
        boolean any = detectedBy.equals(List.of(ANY));
        Map<String, List<String>> idsByDetector = new LinkedHashMap<>();
        Map<String, String> detectorById = new LinkedHashMap<>();
        if (!sources.isEmpty()) {
            var lookup = jdbc.sql(
                            "SELECT id, detector FROM classifier WHERE project_id = :pid AND detector IN (:detectors)"
                                    + (any ? "" : " AND id IN (:ids)"))
                    .param("pid", projectId)
                    .param(
                            "detectors",
                            sources.stream().map(DetectionTable::detectorKind).toList());
            if (!any) {
                lookup = lookup.param("ids", detectedBy);
            }
            lookup.query((rs, n) -> Map.entry(rs.getString("detector"), rs.getString("id")))
                    .list()
                    .forEach(e -> {
                        idsByDetector
                                .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                                .add(e.getValue());
                        detectorById.put(e.getValue(), e.getKey());
                    });
        }
        List<String> arms = new ArrayList<>();
        if (any) {
            List<String> perTable = new ArrayList<>();
            for (DetectionTable s : sources) {
                List<String> ids = idsByDetector.get(s.detectorKind());
                if (ids != null) {
                    perTable.add(exists(s, params, ids));
                }
            }
            arms.add(perTable.isEmpty() ? "FALSE" : "(" + String.join(" OR ", perTable) + ")");
            return arms;
        }
        Map<String, DetectionTable> sourceByDetector =
                sources.stream().collect(Collectors.toMap(DetectionTable::detectorKind, s -> s));
        for (String id : detectedBy) {
            String detector = detectorById.get(id);
            arms.add(detector == null ? "FALSE" : exists(sourceByDetector.get(detector), params, List.of(id)));
        }
        return arms;
    }

    private static String exists(DetectionTable s, Map<String, Object> params, List<String> ids) {
        String name = "detectedBy" + params.size();
        params.put(name, ids);
        return "EXISTS (SELECT 1 FROM " + s.table() + " d WHERE d.project_id = t.project_id"
                + " AND d.classifier_id IN (:" + name + ") AND d.subject_trace_id = t.id" + uncleared(s) + ")";
    }

    private static String uncleared(DetectionTable s) {
        return s.clearable() ? " AND d.cleared_at IS NULL" : "";
    }
}

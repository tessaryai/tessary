// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Shared by the traces and sessions lists. A session matches when, for each picked detection, one of its traces has it
 * and passes every other filter; with no detection picked, when one of its traces would show.
 */
@Component
public class TraceFilters {

    /** Postgres's SQLSTATE for a statement cancelled by {@code statement_timeout}. */
    private static final String QUERY_CANCELED = "57014";

    public static final int MAX_DETECTED_BY = 10;

    private final TraceDetectionRepository detections;

    private final SpanPayloadRepository payloads;

    private final TraceSearchProperties search;

    public TraceFilters(
            TraceDetectionRepository detections, SpanPayloadRepository payloads, TraceSearchProperties search) {
        this.detections = detections;
        this.payloads = payloads;
        this.search = search;
    }

    public static boolean narrows(TraceV2Repository.TraceQuery query) {
        return present(query.model())
                || present(query.kind())
                || present(query.callSite())
                || query.hasCallSite() != null
                || present(query.from())
                || present(query.to())
                || present(query.status())
                || present(query.q())
                || !query.detectedBy().isEmpty()
                || present(query.promptName());
    }

    /** Up to {@link #MAX_DETECTED_BY} distinct classifier ids, or {@link TraceDetectionRepository#ANY} on its own. */
    public static List<String> detectedBy(@Nullable List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> picks = raw.stream()
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
        if (picks.size() > MAX_DETECTED_BY || (picks.contains(TraceDetectionRepository.ANY) && picks.size() > 1)) {
            throw new TessaryException(QueryError.INVALID_DETECTED_BY, MAX_DETECTED_BY);
        }
        return picks;
    }

    /**
     * An unrolled trace (null {@code error_count}) is excluded by any status filter rather than counted healthy.
     * {@code q} matches an id substring or a word-prefix content search; below
     * {@link TraceSearchProperties#getWalkThreshold()} the payload index lists matches once, above it a newest-first
     * walk with {@code OFFSET 0} stops Postgres from re-parsing every payload. A search bounds the transaction to
     * {@link TraceSearchProperties#getTimeoutMs()}.
     */
    public void append(
            String projectId, StringBuilder where, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        appendOthers(projectId, where, params, query);
        for (String arm : arms(projectId, params, query)) {
            where.append(" AND ").append(arm);
        }
    }

    /** The traces of a session that pass the other filters and have any picked detection. */
    public void appendAnyDetection(
            String projectId, StringBuilder where, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        appendOthers(projectId, where, params, query);
        List<String> arms = arms(projectId, params, query);
        if (!arms.isEmpty()) {
            where.append(" AND (").append(String.join(" OR ", arms)).append(')');
        }
    }

    /** One {@code EXISTS} over the session's traces per picked detection, each passing every other filter. */
    public void appendForSessions(
            String projectId, StringBuilder where, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        var traces = new StringBuilder(" AND EXISTS (SELECT 1 FROM trace t WHERE t.project_id = s.project_id"
                + " AND t.session_id = s.id AND NOT t.is_deleted");
        appendOthers(projectId, traces, params, query);
        List<String> arms = arms(projectId, params, query);
        if (arms.isEmpty()) {
            where.append(traces).append(')');
        }
        for (String arm : arms) {
            where.append(traces).append(" AND ").append(arm).append(')');
        }
    }

    private List<String> arms(String projectId, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        return query.detectedBy().isEmpty() ? List.of() : detections.arms(projectId, params, query.detectedBy());
    }

    private void appendOthers(
            String projectId, StringBuilder where, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        addEq(where, params, " AND t.started_at >= :fromTs::timestamptz", "fromTs", query.from());
        addEq(where, params, " AND t.started_at <= :toTs::timestamptz", "toTs", query.to());

        addExists(where, params, "provided_model_name", "model", query.model());
        addExists(where, params, "kind", "kind", query.kind());
        addExists(where, params, "call_site_id", "callSite", query.callSite());
        addPromptExists(where, params, query);
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
            payloads.boundSearch(search.timeoutMs());
            where.append(" AND (t.name ILIKE :q OR t.session_id ILIKE :q OR t.thread_id ILIKE :q"
                    + " OR t.user_id ILIKE :q OR t.id ILIKE :q");
            params.put("q", "%" + escapeLike(q) + "%");
            String words = payloads.prefixQuery(q);
            if (words != null) {
                boolean common =
                        payloads.countMatches(projectId, words, search.walkThreshold()) >= search.walkThreshold();
                where.append(common ? WALK_LANE : INDEX_LANE);
                params.put("qProject", projectId);
                params.put("qWords", words);
            }
            where.append(')');
        }
    }

    private static final String LIVE_PAYLOAD = " FROM span_payload p JOIN span s ON s.project_id = p.project_id"
            + " AND s.trace_id = p.trace_id AND s.id = p.span_id WHERE NOT s.is_deleted AND "
            + SpanPayloadRepository.PAYLOAD_TSVECTOR + " @@ to_tsquery('simple', :qWords)";

    private static final String INDEX_LANE =
            " OR t.id IN (SELECT p.trace_id" + LIVE_PAYLOAD + " AND p.project_id = :qProject)";

    private static final String WALK_LANE =
            " OR EXISTS (SELECT 1" + LIVE_PAYLOAD + " AND p.project_id = t.project_id AND p.trace_id = t.id OFFSET 0)";

    /** A search cancelled by its timeout becomes {@link QueryError#SEARCH_TOO_BROAD}; anything else is rethrown. */
    public static RuntimeException searchFailure(DataAccessException failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return new TessaryException(QueryError.SEARCH_TOO_BROAD, failure);
            }
        }
        return failure;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Carries {@code project_id} because the span primary key leads with it: without it the subquery scans by trace id
     * alone and risks crossing a project boundary.
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

    /** One EXISTS, so the name and the version must sit on the same span; a version alone narrows nothing. */
    private static void addPromptExists(
            StringBuilder where, Map<String, Object> params, TraceV2Repository.TraceQuery query) {
        String name = query.promptName();
        if (name == null || name.isBlank()) {
            return;
        }
        where.append(" AND EXISTS (SELECT 1 FROM span sx WHERE sx.project_id = t.project_id AND sx.trace_id = t.id"
                + " AND NOT sx.is_deleted AND sx.prompt_name = :promptName");
        params.put("promptName", name);
        String version = query.promptVersion();
        if (version != null && !version.isBlank()) {
            where.append(" AND sx.prompt_version = :promptVersion");
            params.put("promptVersion", version);
        }
        where.append(')');
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

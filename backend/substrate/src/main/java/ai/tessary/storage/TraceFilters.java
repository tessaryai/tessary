// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import java.sql.SQLException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The trace list's filters as SQL over a {@code trace t}, shared by the traces list and the sessions list so
 * that a session matches exactly when one of its traces would show in the traces list.
 */
@Component
public class TraceFilters {

    /** Postgres's SQLSTATE for a statement cancelled by {@code statement_timeout}. */
    private static final String QUERY_CANCELED = "57014";

    private final TraceDetectionRepository detections;

    private final SpanPayloadRepository payloads;

    private final TraceSearchProperties search;

    public TraceFilters(
            TraceDetectionRepository detections, SpanPayloadRepository payloads, TraceSearchProperties search) {
        this.detections = detections;
        this.payloads = payloads;
        this.search = search;
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
     *
     * <p>{@code q} matches a trace on either of two lanes. The id lane is a case-insensitive substring of the trace's
     * name, session, thread, user or id, with {@code %} and {@code _} taken literally. The content lane matches when one
     * live span's input or output holds every word of {@code q}, each as a word prefix, in one of two shapes chosen by
     * how many of the project's payloads match:
     *
     * <ul>
     *   <li>Fewer than {@link TraceSearchProperties#getWalkThreshold()}: the payload index lists every match once and
     *       the trace ids are hashed. The subquery binds the project rather than correlating on {@code t}, so Postgres
     *       runs it once.
     *   <li>At the threshold: the word is common, so walking the traces newest first and checking each one's payloads
     *       reaches a page long before listing every match would. {@code OFFSET 0} keeps Postgres from turning the
     *       correlated {@code EXISTS} back into one full hashed list, which for a common word re-parses every payload
     *       in the project.
     * </ul>
     *
     * <p>A search also bounds the rest of the caller's transaction to {@link TraceSearchProperties#getTimeoutMs()} and
     * plans it generically ({@link SpanPayloadRepository#boundSearch}); the caller turns a cancelled statement into
     * {@link QueryError#SEARCH_TOO_BROAD} with {@link #searchFailure}.
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
            payloads.boundSearch(search.getTimeoutMs());
            where.append(" AND (t.name ILIKE :q OR t.session_id ILIKE :q OR t.thread_id ILIKE :q"
                    + " OR t.user_id ILIKE :q OR t.id ILIKE :q");
            params.put("q", "%" + escapeLike(q) + "%");
            String words = payloads.prefixQuery(q);
            if (words != null) {
                boolean common =
                        payloads.countMatches(projectId, words, search.getWalkThreshold()) >= search.getWalkThreshold();
                where.append(common ? WALK_LANE : INDEX_LANE);
                params.put("qProject", projectId);
                params.put("qWords", words);
            }
            where.append(')');
        }

        String detectedBy = query.detectedBy();
        if (present(detectedBy)) {
            detections.appendFilter(projectId, where, params, detectedBy);
        }
    }

    private static final String LIVE_PAYLOAD = " FROM span_payload p JOIN span s ON s.project_id = p.project_id"
            + " AND s.trace_id = p.trace_id AND s.id = p.span_id WHERE NOT s.is_deleted AND "
            + SpanPayloadRepository.PAYLOAD_TSVECTOR + " @@ to_tsquery('simple', :qWords)";

    private static final String INDEX_LANE =
            " OR t.id IN (SELECT p.trace_id" + LIVE_PAYLOAD + " AND p.project_id = :qProject)";

    private static final String WALK_LANE =
            " OR EXISTS (SELECT 1" + LIVE_PAYLOAD + " AND p.project_id = t.project_id AND p.trace_id = t.id OFFSET 0)";

    /**
     * {@code failure} as the error a caller shows: a search cancelled by its timeout is {@link
     * QueryError#SEARCH_TOO_BROAD}, anything else is rethrown as it was.
     */
    public static RuntimeException searchFailure(DataAccessException failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return new TessaryException(QueryError.SEARCH_TOO_BROAD, failure);
            }
        }
        return failure;
    }

    /** {@code value} as a literal inside a {@code LIKE} pattern, whose escape character is the backslash. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

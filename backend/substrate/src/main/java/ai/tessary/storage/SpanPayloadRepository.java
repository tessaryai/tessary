// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static ai.tessary.storage.SessionRepository.requireIso;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Written in the same transaction and under the same {@code event_ts} guard as its span, so the two never disagree on
 * version. Every read is a point lookup or page-scoped join, never a scan.
 */
@Repository
public class SpanPayloadRepository {

    /**
     * Postgres matches an expression index only against an identical expression; any variation still returns correct
     * rows by re-parsing every payload. The cap means search sees the first ~100 KB of input and output.
     */
    public static final String PAYLOAD_TSVECTOR =
            "to_tsvector('simple', left(coalesce(p.input, ''), 100000) || ' ' || left(coalesce(p.output, ''), 100000))";

    /** A one- or two-letter prefix matches nearly every payload. */
    static final int MIN_PREFIX_LENGTH = 3;

    private static final String COLS = "project_id, trace_id, span_id, input, output, attributes::text AS attributes, "
            + "provided_usage::text AS provided_usage, event_ts, redactions::text AS redactions";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    public SpanPayloadRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static final String UPSERT_SQL = """
                        INSERT INTO span_payload (project_id, trace_id, span_id, input, output, attributes,
                                                  provided_usage, event_ts, redactions)
                        VALUES (:pid, :traceId, :spanId, :input, :output, :attributes::jsonb,
                                :providedUsage::jsonb, :eventTs::timestamptz, :redactions::jsonb)
                        ON CONFLICT (project_id, trace_id, span_id) DO UPDATE
                           SET input          = excluded.input,
                               output         = excluded.output,
                               attributes     = excluded.attributes,
                               provided_usage = excluded.provided_usage,
                               event_ts       = excluded.event_ts,
                               redactions     = excluded.redactions
                         WHERE excluded.event_ts >= span_payload.event_ts
                        """;

    private static MapSqlParameterSource params(SpanPayloadRow row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("pid", row.projectId());
        source.addValue("traceId", row.traceId());
        source.addValue("spanId", row.spanId());
        source.addValue("input", row.input());
        source.addValue("output", row.output());
        source.addValue("attributes", row.attributes());
        source.addValue("providedUsage", row.providedUsage());
        source.addValue("eventTs", row.eventTs());
        source.addValue("redactions", row.redactions());
        return source;
    }

    /**
     * Split by Postgres's own {@code 'simple'} parser, the one the index used. {@code quote_literal} keeps typed
     * operators from being read as tsquery syntax.
     */
    public @Nullable String prefixQuery(String text) {
        return jdbc.sql("""
                        SELECT string_agg(quote_literal(lexeme)
                                          || CASE WHEN length(lexeme) >= :minPrefix THEN ':*' ELSE '' END, ' & ')
                        FROM unnest(to_tsvector('simple', :text))
                        """)
                .param("text", text)
                .param("minPrefix", MIN_PREFIX_LENGTH)
                .query((rs, n) -> Optional.ofNullable(rs.getString(1)))
                .single()
                .orElse(null);
    }

    /** The index intersects the project's rows with the words' rows, so another tenant's matches are never fetched. */
    public int countMatches(String projectId, String words, int cap) {
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM span_payload p WHERE p.project_id = :pid AND "
                        + PAYLOAD_TSVECTOR + " @@ to_tsquery('simple', :words) LIMIT :cap) m")
                .param("pid", projectId)
                .param("words", words)
                .param("cap", cap)
                .query(Integer.class)
                .single();
    }

    /**
     * Outside a transaction it does neither. The generic plan is deliberate: shown a common word, the planner re-parses
     * payloads instead of using the index (1.9 s instead of 39 ms on 300k spans).
     */
    public void boundSearch(long timeoutMs) {
        jdbc.sql("SELECT set_config('statement_timeout', :ms, true),"
                        + " set_config('plan_cache_mode', 'force_generic_plan', true)")
                .param("ms", Long.toString(timeoutMs))
                .query()
                .listOfRows();
    }

    /** Under the same {@code event_ts} guard as {@link SpanRepository#upsertAll}: ties go to the latest arrival. */
    public void upsertAll(List<SpanPayloadRow> rows) {
        int[] applied = named.batchUpdate(
                UPSERT_SQL, rows.stream().map(SpanPayloadRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    public Optional<SpanPayloadRow> find(String projectId, String traceId, String spanId) {
        return jdbc.sql("SELECT " + COLS + " FROM span_payload"
                        + " WHERE project_id = :pid AND trace_id = :tid AND span_id = :sid")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("sid", spanId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** A list surface must not call it; payloads may age out ahead of spans, and lists stay single-table reads. */
    public List<SpanPayloadRow> listByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + COLS + " FROM span_payload WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Unordered; a payload that has aged out is absent. */
    public List<SpanPayloadRow> listByTraceIds(String projectId, Collection<String> traceIds) {
        return jdbc.sql("SELECT " + COLS + " FROM span_payload WHERE project_id = :pid AND trace_id = ANY(:ids)")
                .param("pid", projectId)
                .param("ids", traceIds.toArray(String[]::new))
                .query((rs, n) -> map(rs))
                .list();
    }

    /** For a page whose spans come from many traces. A span whose payload aged out is absent. */
    public List<SpanPayloadRow> listByKeys(String projectId, List<SpanKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        var spec = jdbc.sql("SELECT " + COLS + " FROM span_payload WHERE project_id = :pid AND "
                        + SpanKey.tupleIn("trace_id", "span_id", keys.size()))
                .param("pid", projectId);
        return SpanKey.bind(spec, keys).query((rs, n) -> map(rs)).list();
    }

    /**
     * "No payload row" (aged out) and "empty input" are different facts. An index-only probe, so the page's
     * conversations are never read just to be thrown away.
     */
    public Set<SpanKey> existingKeys(String projectId, List<SpanKey> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        var spec = jdbc.sql("SELECT trace_id, span_id FROM span_payload WHERE project_id = :pid AND "
                        + SpanKey.tupleIn("trace_id", "span_id", keys.size()))
                .param("pid", projectId);
        return Set.copyOf(SpanKey.bind(spec, keys)
                .query((rs, n) -> new SpanKey(rs.getString("trace_id"), rs.getString("span_id")))
                .list());
    }

    private static SpanPayloadRow map(ResultSet rs) throws SQLException {
        return new SpanPayloadRow(
                rs.getString("project_id"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("input"),
                rs.getString("output"),
                rs.getString("attributes"),
                rs.getString("provided_usage"),
                requireIso(rs, "event_ts"),
                rs.getString("redactions"));
    }
}

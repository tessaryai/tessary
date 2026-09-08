// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import static ai.tessary.evals.storage.SessionRepository.requireIso;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for {@code span_payload} (substrate-model.md §4).
 *
 * <p>The payload is written in the same transaction as its span row, under the same {@code event_ts}
 * guard, so span and payload can never disagree about which version they hold. A payload that survived a
 * span replacement would show one version's prompt beside another version's token counts, and nothing
 * downstream could tell.
 *
 * <p><b>Every read here is a deliberate step outside the list surfaces.</b> The payload is the only place the
 * raw conversation lives, so the reads are point lookups and page-scoped joins — never a scan — and each one
 * is called from a surface that has already narrowed to the rows a caller asked to read in full.
 */
@Repository
public class SpanPayloadRepository {

    private static final String COLS = "project_id, trace_id, span_id, input, output, attributes::text AS attributes, "
            + "provided_usage::text AS provided_usage, event_ts";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    public SpanPayloadRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static final String UPSERT_SQL = """
                        INSERT INTO span_payload (project_id, trace_id, span_id, input, output, attributes,
                                                  provided_usage, event_ts)
                        VALUES (:pid, :traceId, :spanId, :input, :output, :attributes::jsonb,
                                :providedUsage::jsonb, :eventTs::timestamptz)
                        ON CONFLICT (project_id, trace_id, span_id) DO UPDATE
                           SET input          = excluded.input,
                               output         = excluded.output,
                               attributes     = excluded.attributes,
                               provided_usage = excluded.provided_usage,
                               event_ts       = excluded.event_ts
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
        return source;
    }

    /**
     * One JDBC batch for a whole batch of payloads, the same statement and guard as {@link #upsert} per row (#984 M2).
     */
    public void upsertAll(List<SpanPayloadRow> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                UPSERT_SQL, rows.stream().map(SpanPayloadRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    /**
     * Last-write-wins upsert under the same {@code event_ts} guard as {@link SpanRepository#upsert}, ties
     * to the latest arrival.
     *
     * @return the number of rows written: 0 when the guard rejected an older version.
     */
    public int upsert(SpanPayloadRow row) {
        return jdbc.sql(UPSERT_SQL).paramSource(params(row)).update();
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

    /**
     * Every payload of one trace, read through the primary-key prefix.
     *
     * <p>The trace detail is the surface this is for, and it reads a whole trace at once rather than a row
     * per span. A LIST surface must not call it: the traces list stays a single-table read, which is why
     * payloads can age out ahead of spans without breaking anything a list shows. {@link #listByKeys} and
     * {@link #existingKeys} are how the one list that does need payload facts asks for exactly the page it is
     * rendering, instead of for every payload of every trace on it.
     */
    public List<SpanPayloadRow> listByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + COLS + " FROM span_payload WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The payloads of an arbitrary set of spans, in ONE query — for a page whose spans come from many traces
     * (a search page), where {@link #listByTrace} would be a query per trace AND would read the payloads of
     * every span the page did not select.
     *
     * <p>Unordered, and possibly shorter than {@code keys}: a span whose payload has aged out (spec §10) is
     * simply absent here.
     */
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
     * Which of {@code keys} have a payload row at all — existence, without the text.
     *
     * <p>The distinction this answers is not cosmetic. Payloads age out ahead of spans, so "no payload row"
     * and "a payload whose input was empty" are different facts, and a list that renders them identically
     * tells its reader "this call had no input" when the truth is "we no longer hold it".
     *
     * <p>Separate from {@link #listByKeys} because the projection is the point: the key columns alone are an
     * index-only probe of the primary key, whereas learning the same thing from {@code listByKeys} would drag
     * every conversation on the page through memory to throw all of it away.
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
                requireIso(rs, "event_ts"));
    }
}

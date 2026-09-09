// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.ingest.PreviewCursor;
import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The evidence set behind a finding: append-only, uncapped, written in the same transaction that opens
 * the finding while the rows it points at are still guaranteed to exist.
 *
 * <p>The whole population a classifier's claim rests on is written down, not a sample: a claim about a
 * population can't be audited against a sample someone else drew and didn't describe, so sampling becomes
 * a read-time decision the reading agent states instead. Retention treats these rows as pinned, so a
 * large evidence set widens what it has to keep, but that's the trade auditability costs.
 *
 * <p>Counts are written alongside the rows: {@link #record} bumps {@code finding.evidence_counts} in the
 * same call, so a reader can tell "the population is fully enumerated" from "the write was interrupted"
 * without a {@code count(*)}.
 *
 * <p>Rows are appended, never rewritten: a classifier may add witnesses while a finding is open, but
 * never re-points what a ruling was made about. {@code ux_finding_evidence_ref} makes a repeated
 * reference a no-op, so a sweep that re-reads the same window is idempotent.
 */
@Repository
public class FindingEvidenceRepository {

    /**
     * Rows per INSERT statement. Not a cap on the set: the caller's whole list is written, in as many
     * statements as it takes. The bound is the wire protocol's: a statement carries at most 65535 bind
     * parameters, and each row here binds five that are not hoisted into the shared ones.
     */
    private static final int INSERT_CHUNK = 2000;

    private static final String COLS =
            "id, project_id, finding_id, session_id, trace_id, span_id, role, rank, created_at";

    /**
     * The one ordering this table is read in, by every reader: the detector's own order inside each role.
     * ASC is NULLS LAST in Postgres, which is also the order {@code ix_finding_evidence_finding
     * (finding_id, role, rank)} stores, so the scan and the sort agree.
     */
    private static final String ORDER = "role, rank NULLS LAST, id";

    /**
     * The other ordering: most significant role first, for the readers that truncate. Ordering by
     * {@link #ORDER} instead would sort the vocabulary alphabetically, spending the budget on
     * {@code baseline} (the healthy side, compared against) before it reached the flagged one.
     */
    private static final String ROLE_SIGNIFICANCE = "CASE role WHEN 'exemplar' THEN 0 WHEN 'changepoint' THEN 1"
            + " WHEN 'witness' THEN 2 WHEN 'member' THEN 3 ELSE 4 END";

    /**
     * Where a null {@code rank} sorts in the cursor comparison, past every real rank, matching the
     * NULLS LAST in {@link #ORDER}. Not a value anything writes; {@link #record} always assigns a rank.
     */
    private static final int RANK_TAIL = Integer.MAX_VALUE;

    /**
     * How much of a span's input and output {@link #spanPage} returns per row.
     *
     * <p>Enough for a reader to recognise the call and see the error it returned; far short of enough
     * to render the payload, which is what the trace view is for. Applied with SQL {@code left()} so
     * the bytes never leave Postgres rather than being fetched and then thrown away.
     */
    private static final int PREVIEW_CHARS = 200;

    /** Cursor field separator: the unit separator, as in every other keyset cursor on this stack. */
    private static final char CURSOR_SEP = '\u001f';

    /** The cursor generation. A token of any other shape restarts at the first page rather than mis-resuming. */
    private static final String CURSOR_VERSION = "v1";

    private final JdbcClient jdbc;

    public FindingEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One reference the classifier chose, at whichever grain it saw the claim. */
    public record Ref(
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId) {

        /** A turn, the grain every classifier writing today records. */
        public static Ref trace(String traceId) {
            return new Ref(null, traceId, null);
        }

        /** One step inside a turn. Both halves of the composite span key, or it resolves to nothing. */
        public static Ref span(String traceId, String spanId) {
            return new Ref(null, traceId, spanId);
        }

        /** The container above a turn. */
        public static Ref session(String sessionId) {
            return new Ref(sessionId, null, null);
        }
    }

    /**
     * Record {@code refs} under one role, in order. Returns how many rows were actually written;
     * repeats are silently skipped, so this is safe to call on every sweep pass, not just the pass that
     * opened the finding.
     *
     * <p>Written in multi-row INSERTs rather than one statement per ref, since the set can be large.
     * {@code rank} is the caller's order, offset past whatever is already stored, so a second batch of
     * witnesses sorts after the first. It's assigned positionally across the batch, so a re-record leaves
     * gaps where rows conflicted rather than renumbering: rank orders the set, it doesn't enumerate it.
     */
    public int record(String projectId, String findingId, String role, List<Ref> refs, String now) {
        if (refs.isEmpty()) return 0;
        // Distinct-preserving: a caller that hands the same trace twice must not spend two ranks on it.
        Map<String, Ref> unique = new LinkedHashMap<>();
        for (Ref ref : refs) {
            if (ref.sessionId() == null && ref.traceId() == null) continue;
            unique.putIfAbsent(key(ref), ref);
        }
        if (unique.isEmpty()) return 0;
        List<Ref> ordered = List.copyOf(unique.values());
        int rank = countFor(findingId, role);
        int written = 0;
        for (int from = 0; from < ordered.size(); from += INSERT_CHUNK) {
            List<Ref> chunk = ordered.subList(from, Math.min(from + INSERT_CHUNK, ordered.size()));
            StringBuilder sql = new StringBuilder("INSERT INTO finding_evidence (" + COLS + ") VALUES ");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append("(:id")
                        .append(i)
                        .append(", :pid, :fid, :session")
                        .append(i)
                        .append(", :trace")
                        .append(i)
                        .append(", :span")
                        .append(i)
                        .append(", :role, :rank")
                        .append(i)
                        .append(", :now)");
            }
            sql.append(" ON CONFLICT DO NOTHING");
            var spec = jdbc.sql(sql.toString())
                    .param("pid", projectId)
                    .param("fid", findingId)
                    .param("role", role)
                    .param("now", now);
            for (int i = 0; i < chunk.size(); i++) {
                Ref ref = chunk.get(i);
                spec = spec.param("id" + i, Ids.ulid())
                        .param("session" + i, ref.sessionId())
                        .param("trace" + i, ref.traceId())
                        .param("span" + i, ref.spanId())
                        .param("rank" + i, rank + from + i);
            }
            written += spec.update();
        }
        recordCount(projectId, findingId, role, written);
        return written;
    }

    /**
     * Add what this call wrote to the finding's per-role tally. Accumulates rather than assigns, since
     * a role can be appended to across passes. Deliberately does not touch {@code updated_at}: recording
     * what a claim already rested on is not a change to the claim.
     */
    private void recordCount(String projectId, String findingId, String role, int written) {
        if (written == 0) return;
        jdbc.sql("""
                UPDATE finding
                   SET evidence_counts = jsonb_set(
                           COALESCE(evidence_counts, '{}'::jsonb),
                           ARRAY[CAST(:role AS text)],
                           to_jsonb(COALESCE((evidence_counts ->> CAST(:role AS text))::bigint, 0) + :written))
                 WHERE project_id = :pid AND id = :fid
                """)
                .param("role", role)
                .param("written", (long) written)
                .param("pid", projectId)
                .param("fid", findingId)
                .update();
    }

    /**
     * Re-point one role's whole set at {@code refs}, discarding what was there.
     *
     * <p>The one exception to append-never-rewrite, and it's narrow on purpose: callers must establish
     * that no ruling stands on the finding first ({@code FindingRepository.Recorded#ruled()} is that
     * test), because a distribution shift re-fires on every window close and a finding that kept only its
     * opening window's rows would end up citing evidence never drawn from the traffic it claims about.
     * Deleting also unpins the dropped rows from retention, which is the right trade only under the same
     * condition.
     *
     * <p>An empty {@code refs} is a no-op rather than a truncation, since some reference sets (a merge of
     * per-day histograms rather than a window) legitimately carry no rows at all.
     */
    public int replace(String projectId, String findingId, String role, List<Ref> refs, String now) {
        if (refs.isEmpty()) return 0;
        jdbc.sql("DELETE FROM finding_evidence WHERE project_id = :pid AND finding_id = :fid AND role = :role")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("role", role)
                .update();
        // Zeroed rather than left to drift: `record` accumulates onto this tally, so a stale count would
        // survive the delete and report a population larger than the rows behind it.
        jdbc.sql("""
                UPDATE finding
                   SET evidence_counts = jsonb_set(
                           COALESCE(evidence_counts, '{}'::jsonb),
                           ARRAY[CAST(:role AS text)],
                           to_jsonb(0))
                 WHERE project_id = :pid AND id = :fid
                """)
                .param("role", role)
                .param("pid", projectId)
                .param("fid", findingId)
                .update();
        return record(projectId, findingId, role, refs, now);
    }

    /** Convenience for the common single-exemplar write. */
    public int recordExemplarTrace(String projectId, String findingId, @Nullable String traceId, String now) {
        if (traceId == null || traceId.isBlank()) return 0;
        return record(projectId, findingId, FindingEvidenceRow.Role.EXEMPLAR, List.of(Ref.trace(traceId)), now);
    }

    /**
     * The finding's whole evidence set, role then rank, the order the detector wrote it in.
     *
     * <p>Unpaged, so it is for the callers that render a finding's own page and hold the result in
     * memory. A population can now be six figures; a reader that does not need all of it at once takes
     * {@link #page} instead.
     */
    public List<FindingEvidenceRow> listByFinding(String projectId, String findingId) {
        return jdbc.sql("SELECT " + COLS + " FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id = :fid"
                        + " ORDER BY " + ORDER)
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** A page of a finding's evidence and the cursor that resumes after it, null when this was the last. */
    public record Page(
            List<FindingEvidenceRow> rows, @Nullable String nextCursor) {}

    /**
     * One page of a finding's evidence in the detector's own order, optionally narrowed to a role.
     *
     * <p>The keyset is {@code (role, rank, id)}, not {@code rank}: rank is neither dense nor unique
     * across the set (two roles number from zero independently, and gaps appear where a re-record
     * conflicted), so paging on rank alone would skip rows and interleave roles. The id tiebreak makes
     * the order total.
     *
     * <p>A null rank sorts to the tail, matching {@code ix_finding_evidence_finding}'s NULLS LAST, and the
     * cursor comparison coalesces it to {@link #RANK_TAIL} so those rows stay reachable on later pages
     * rather than silently dropping out.
     *
     * <p>Over-fetches by one, as every keyset reader here does, so a next page needs no count.
     */
    public Page page(String projectId, String findingId, @Nullable String role, int limit, @Nullable String cursor) {
        Key key = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLS + " FROM finding_evidence WHERE project_id = :pid AND finding_id = :fid");
        if (role != null) sql.append(" AND role = :role");
        if (key != null) {
            sql.append(" AND (role, COALESCE(rank, ")
                    .append(RANK_TAIL)
                    .append("), id) > (CAST(:cRole AS text), CAST(:cRank AS integer), CAST(:cId AS text))");
        }
        sql.append(" ORDER BY ").append(ORDER).append(" LIMIT :n");
        var spec = jdbc.sql(sql.toString())
                .param("pid", projectId)
                .param("fid", findingId)
                .param("n", limit + 1);
        if (role != null) spec = spec.param("role", role);
        if (key != null) {
            spec = spec.param("cRole", key.role()).param("cRank", key.rank()).param("cId", key.id());
        }
        List<FindingEvidenceRow> rows = spec.query((rs, n) -> map(rs)).list();
        if (rows.size() <= limit) return new Page(rows, null);
        // Seeded from the last row of THIS page, never from the over-fetched row: that one is the first
        // row of the next page and seeding from it would skip it.
        return new Page(List.copyOf(rows.subList(0, limit)), encodeCursor(rows.get(limit - 1)));
    }

    /**
     * One evidence ref with the span it points at, what the finding page's evidence table renders.
     *
     * <p>Span-shaped, not trace-shaped: a {@code member} is a span, and tool error enumerates one ref per
     * call, so several refs routinely name the same trace. {@code tokens} and {@code cost} are null on a
     * tool span since they're an LLM span's properties, and the table simply leaves those cells empty.
     */
    public record SpanRef(
            String role,
            @Nullable Integer rank,
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String name,
            @Nullable String kind,
            @Nullable String status,
            @Nullable String level,
            @Nullable String errorType,
            @Nullable String startedAt,
            @Nullable Long latencyMs,
            @Nullable Long totalTokens,
            @Nullable Double totalCost,
            @Nullable String model,
            @Nullable String callSiteId,
            /**
             * The first {@link #PREVIEW_CHARS} characters of what the span was given and what it
             * returned, or null where the payload was never written or has aged out. Truncated in SQL
             * rather than Java, since a single LLM span's output can run to megabytes.
             */
            @Nullable String inputPreview,
            @Nullable String outputPreview) {}

    /** A page of {@link SpanRef}s and the cursor that resumes after it, null when this was the last. */
    public record SpanPage(List<SpanRef> rows, @Nullable String nextCursor) {}

    /**
     * {@link #page}, with the span each ref names joined on. Same keyset, order and over-fetch, so a
     * reader paging this and a reader paging the refs see the population in the same sequence; kept
     * separate from {@link #page} because the MCP door returns ids and no bodies, while a person looking
     * at a table needs the row to say something.
     *
     * <p>The join is LEFT and falls back to the trace's logical root for a trace-grain ref, so a
     * behaviour-drift finding still renders a name and a time rather than an id and four dashes. A ref
     * whose substrate has aged out keeps its ids and carries nulls.
     *
     * <p>Payloads are normally kept off list and sweep surfaces; this join is the bounded exception, a
     * keyset page of at most {@code limit} spans, 1:1 on the span primary key, with columns truncated in
     * SQL so a page costs {@code limit × 2 × PREVIEW_CHARS} however large the payloads behind it are.
     */
    public SpanPage spanPage(
            String projectId, String findingId, @Nullable String role, int limit, @Nullable String cursor) {
        Key key = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("SELECT e.id AS evidence_id, e.role, e.rank, e.session_id,"
                + " e.trace_id, e.span_id,"
                + " s.name, s.kind, s.status, s.level, s.error_type, s.started_at, s.latency_ms,"
                + " s.total_tokens, s.total_cost, s.provided_model_name, s.call_site_id,"
                + " left(pl.input, " + PREVIEW_CHARS + ") AS input_preview,"
                + " left(pl.output, " + PREVIEW_CHARS + ") AS output_preview"
                + " FROM finding_evidence e"
                + " LEFT JOIN span s ON s.project_id = e.project_id AND s.trace_id = e.trace_id"
                + "   AND (s.id = e.span_id OR (e.span_id IS NULL AND s.is_logical_root))"
                + " LEFT JOIN span_payload pl ON pl.project_id = s.project_id"
                + "   AND pl.trace_id = s.trace_id AND pl.span_id = s.id"
                + " WHERE e.project_id = :pid AND e.finding_id = :fid");
        if (role != null) sql.append(" AND e.role = :role");
        if (key != null) {
            sql.append(" AND (e.role, COALESCE(e.rank, ")
                    .append(RANK_TAIL)
                    .append("), e.id) > (CAST(:cRole AS text), CAST(:cRank AS integer), CAST(:cId AS text))");
        }
        sql.append(" ORDER BY e.role, e.rank NULLS LAST, e.id LIMIT :n");
        var spec = jdbc.sql(sql.toString())
                .param("pid", projectId)
                .param("fid", findingId)
                .param("n", limit + 1);
        if (role != null) spec = spec.param("role", role);
        if (key != null) {
            spec = spec.param("cRole", key.role()).param("cRank", key.rank()).param("cId", key.id());
        }
        record Seeded(SpanRef ref, String evidenceId) {}
        List<Seeded> rows = spec.query((rs, n) -> new Seeded(mapSpan(rs), rs.getString("evidence_id")))
                .list();
        List<SpanRef> refs = rows.stream().map(Seeded::ref).toList();
        if (refs.size() <= limit) return new SpanPage(refs, null);
        // Seeded from the last row of THIS page, exactly as page() does, the over-fetched row is the
        // first row of the NEXT page, and seeding from it would skip it.
        Seeded last = rows.get(limit - 1);
        return new SpanPage(
                List.copyOf(refs.subList(0, limit)),
                encodeCursor(last.ref().role(), last.ref().rank(), last.evidenceId()));
    }

    /**
     * How many refs of each role survive, counted now, every role in the vocabulary, zeros included.
     * Deliberately not {@code finding.evidence_counts} (the count as written): refs age out with the
     * substrate they point at, so this comes in at or below the stored number. A caller that wants the
     * stored number reads {@link FindingRow#evidenceCount}; a caller that wants what it can actually open
     * reads this.
     */
    public Map<String, Long> countsByRole(String projectId, String findingId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String role : FindingEvidenceRow.Role.ALL) out.put(role, 0L);
        record Tally(String role, long count) {}
        for (Tally tally : jdbc.sql("SELECT role, count(*) AS n FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id = :fid GROUP BY role")
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> new Tally(rs.getString("role"), rs.getLong("n")))
                .list()) {
            out.put(tally.role(), tally.count());
        }
        return out;
    }

    /**
     * The trace a Layer-2 run is pointed at: the lowest-ranked {@code exemplar}. Empty when the
     * detector recorded none, or when the trace it recorded has since aged out and its row went with
     * it, Layer 2 refuses to rule on a finding it cannot read, and this is where that shows up.
     */
    public Optional<String> exemplarTraceId(String projectId, String findingId) {
        return jdbc.sql("SELECT trace_id FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id = :fid AND role = :role AND trace_id IS NOT NULL"
                        + " ORDER BY rank NULLS LAST, id LIMIT 1")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("role", FindingEvidenceRow.Role.EXEMPLAR)
                .query(String.class)
                .optional();
    }

    /**
     * The trace a Layer-2 run is anchored to: the most significant trace-grain ref the finding carries,
     * whatever role wrote it. Empty only when the finding cites no trace at all.
     *
     * <p>Role-agnostic on purpose: not every classifier cites {@code exemplar} (tool error cites
     * {@code witness} and {@code member}; metric drift cites {@code member} and {@code baseline}), so
     * demanding an exemplar would refuse analysis on findings that never write one.
     *
     * <p>This resolves a session and a deploy for the job rather than pointing the agent at what to
     * read, it pages evidence through MCP and decides that itself. The significance order still
     * matters: a {@code baseline} trace would name the healthy side's deploy.
     */
    public Optional<String> anchorTraceId(String projectId, String findingId) {
        return jdbc.sql("SELECT trace_id FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id = :fid AND trace_id IS NOT NULL"
                        + " ORDER BY " + ROLE_SIGNIFICANCE + ", rank NULLS LAST, id LIMIT 1")
                .param("pid", projectId)
                .param("fid", findingId)
                .query(String.class)
                .optional();
    }

    /**
     * The traces a set of findings recorded, most significant first, the case page's exemplar list and
     * the grader lane's input, read in one query so a page of cases is not N of them.
     *
     * <p>"Most significant" is an explicit order, not the role column's: alphabetical order would put
     * {@code baseline} (the healthy side, compared against) ahead of the exemplars. Every caller here
     * truncates, and spending that budget on the wrong side of the comparison would report that graders
     * found nothing wrong, true of a baseline, and meaningless for the finding.
     */
    public Map<String, List<String>> traceIdsByFinding(String projectId, List<String> findingIds) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (findingIds.isEmpty()) return out;
        record Pair(String findingId, String traceId) {}
        for (Pair pair : jdbc.sql("SELECT finding_id, trace_id FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id IN (:ids) AND trace_id IS NOT NULL"
                        + " ORDER BY finding_id, " + ROLE_SIGNIFICANCE + ", rank NULLS LAST, id")
                .param("pid", projectId)
                .param("ids", findingIds)
                .query((rs, n) -> new Pair(rs.getString("finding_id"), rs.getString("trace_id")))
                .list()) {
            List<String> traces = out.computeIfAbsent(pair.findingId(), k -> new ArrayList<>());
            if (!traces.contains(pair.traceId())) traces.add(pair.traceId());
        }
        return out;
    }

    /**
     * The evidence sets of many findings at once, keyed by finding id, what a page of findings needs so
     * rendering a list is one query rather than one per row. Findings with no surviving evidence are
     * simply absent from the map: an empty set is a real state and the caller renders it as one.
     */
    public Map<String, List<FindingEvidenceRow>> listByFindings(String projectId, List<String> findingIds) {
        Map<String, List<FindingEvidenceRow>> out = new LinkedHashMap<>();
        if (findingIds.isEmpty()) return out;
        for (FindingEvidenceRow row : jdbc.sql("SELECT " + COLS + " FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id IN (:ids)"
                        + " ORDER BY finding_id, role, rank NULLS LAST, id")
                .param("pid", projectId)
                .param("ids", findingIds)
                .query((rs, n) -> map(rs))
                .list()) {
            out.computeIfAbsent(row.findingId(), k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    private static FindingEvidenceRow map(ResultSet rs) throws SQLException {
        return new FindingEvidenceRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("finding_id"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("role"),
                (Integer) rs.getObject("rank"),
                rs.getString("created_at"));
    }

    /** The point on {@link #ORDER} a cursor resumes after. */
    private record Key(String role, int rank, String id) {}

    /**
     * Decode a cursor, or null for "start at the first page".
     *
     * <p>Null covers every unusable token, absent, corrupt, a foreign generation, because there is
     * nothing a caller could do differently about any of them and the first page is a correct answer to
     * all three. Same posture as the trace and case cursors.
     */
    private static @Nullable Key decodeCursor(@Nullable String cursor) {
        String token = PreviewCursor.decode(cursor).token();
        if (token == null) return null;
        String[] parts = token.split(String.valueOf(CURSOR_SEP), -1);
        if (parts.length != 4 || !CURSOR_VERSION.equals(parts[0])) return null;
        if (parts[1].isEmpty() || parts[3].isEmpty()) return null;
        try {
            return new Key(parts[1], Integer.parseInt(parts[2]), parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encodeCursor(FindingEvidenceRow last) {
        return encodeCursor(last.role(), last.rank(), last.id());
    }

    /** The keyset, from its three parts, shared so the ref page and the span page tokenize identically. */
    private static String encodeCursor(String role, @Nullable Integer rank, String id) {
        return PreviewCursor.encode(
                CURSOR_VERSION + CURSOR_SEP + role + CURSOR_SEP + (rank == null ? RANK_TAIL : rank) + CURSOR_SEP + id,
                0);
    }

    private static SpanRef mapSpan(ResultSet rs) throws SQLException {
        return new SpanRef(
                rs.getString("role"),
                (Integer) rs.getObject("rank"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("level"),
                rs.getString("error_type"),
                // ISO-8601, like every other instant this surface returns. rs.getString on a timestamptz
                // gives Postgres's own rendering ("2026-08-03 08:50:24.015+00"), which no Date parser on
                // the other side reads the same way twice.
                rs.getObject("started_at", OffsetDateTime.class) == null
                        ? null
                        : rs.getObject("started_at", OffsetDateTime.class)
                                .toInstant()
                                .toString(),
                (Long) rs.getObject("latency_ms"),
                (Long) rs.getObject("total_tokens"),
                rs.getObject("total_cost") == null
                        ? null
                        : rs.getBigDecimal("total_cost").doubleValue(),
                rs.getString("provided_model_name"),
                rs.getString("call_site_id"),
                rs.getString("input_preview"),
                rs.getString("output_preview"));
    }

    private int countFor(String findingId, String role) {
        // count(*) is never null, so this is unwrapped rather than defaulted.
        return jdbc.sql("SELECT count(*) FROM finding_evidence WHERE finding_id = :fid AND role = :role")
                .param("fid", findingId)
                .param("role", role)
                .query(Long.class)
                .single()
                .intValue();
    }

    private static String key(Ref ref) {
        return String.valueOf(ref.sessionId()) + ' ' + ref.traceId() + ' ' + ref.spanId();
    }
}

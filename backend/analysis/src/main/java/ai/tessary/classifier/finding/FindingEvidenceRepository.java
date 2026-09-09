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
 * The evidence set behind a finding: append-only, UNCAPPED, written in the same transaction that opens
 * the finding while the rows it points at are still guaranteed to exist (spec §5).
 *
 * <p><b>The population is the claim, so the whole population is written down.</b> This used to enforce
 * a per-role cap of 50, on the reasoning that evidence is what retention consults before deleting
 * substrate ({@code RetentionRepository} reads {@code finding_evidence} at every grain) and an unbounded
 * set is therefore an unbounded pin. That reasoning was never wrong about the cost — removing the cap
 * really does widen the pin, by as much as the busiest detector's window. It was wrong about the trade.
 * Layer 2 audits whether a finding's claim is true, sufficiently sampled and properly evidenced, and a
 * claim about a population cannot be audited against a sample somebody else drew and did not describe:
 * the auditor cannot tell a real shift from the selection that produced its evidence. So the classifier
 * decides what its claim rests on — if that is 200k rows it writes 200k refs — and sampling becomes a
 * read-time decision the reading agent has to state. The pin widens deliberately; retention is what pays
 * for a claim being auditable.
 *
 * <p><b>Counts are written with the rows.</b> Because a claim can now be arbitrarily large, its size has
 * to be readable without a {@code count(*)}: {@link #record} bumps {@code finding.evidence_counts} in the
 * same call, so a reader can tell "I hold the enumerated population" from "the write was interrupted".
 *
 * <p><b>Append, never rewrite.</b> A classifier may add witnesses while a finding is open; it never
 * re-points what a ruling was made about. {@code ux_finding_evidence_ref} makes a repeated reference a
 * no-op rather than a duplicate, so a sweep that re-reads the same window is idempotent.
 */
@Repository
public class FindingEvidenceRepository {

    /**
     * Rows per INSERT statement. Not a cap on the set — the caller's whole list is written, in as many
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
     * The other ordering: most significant role first, for the readers that TRUNCATE. Ordering those by
     * {@link #ORDER} would sort the vocabulary alphabetically and spend the budget on {@code baseline} —
     * the healthy side, the traces the classifier compared AGAINST — before it reached the flagged one.
     * One constant because two copies of a ladder drift, and a drifted copy reads as a deliberate
     * difference in what a caller considers significant.
     */
    private static final String ROLE_SIGNIFICANCE = "CASE role WHEN 'exemplar' THEN 0 WHEN 'changepoint' THEN 1"
            + " WHEN 'witness' THEN 2 WHEN 'member' THEN 3 ELSE 4 END";

    /**
     * Where a null {@code rank} sorts in the cursor comparison — past every real rank, matching the
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

        /** A turn — the grain every classifier writing today records. */
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
     * Record {@code refs} under one role, in order. Returns how many rows were actually written —
     * repeats are silently skipped, which is what makes this safe to call on every sweep pass rather
     * than only on the pass that opened the finding.
     *
     * <p>Written in multi-row INSERTs rather than one statement per ref. The set is a population now,
     * so a statement per row would be a round trip per measured trace; the write runs once per
     * finding-open, so it is bounded by detector fire rate rather than by traffic.
     *
     * <p>{@code rank} is the caller's order, offset past whatever is already stored, so a second batch
     * of witnesses sorts after the first instead of interleaving with it. It is assigned positionally
     * across the batch, so a re-record leaves GAPS where rows conflicted rather than renumbering —
     * rank orders the set, it does not enumerate it.
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
     * Add what this call wrote to the finding's per-role tally.
     *
     * <p>Kept here rather than left to each writer for the reason the old cap was: a number every
     * caller has to remember to bump is a number that will be wrong. It accumulates rather than
     * assigns, because a role can be appended to across passes, and it deliberately does not touch
     * {@code updated_at} — recording what a claim already rested on is not a change to the claim, and
     * every liveness read on this table is a comparison against {@code last_seen_at}.
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
     * <p><b>This is the exception to append-never-rewrite, and it is narrow on purpose.</b> The rule
     * exists so that nothing re-points what a ruling was made about; it says nothing about a finding
     * nobody has ruled on. A distribution shift re-fires on every window close and its payload is
     * replaced each time, so a finding that only ever kept its OPENING window's rows ends up stating a
     * claim measured over traffic its evidence was never drawn from — which is precisely what an
     * auditor then reports as a contradiction. Callers must therefore establish that no ruling stands
     * before calling this; {@code FindingRepository.Recorded#ruled()} is that test.
     *
     * <p>Deleting also unpins: {@code RetentionRepository} reads this table to decide what substrate
     * survives, so the rows dropped here stop being protected. That is the right trade only under the
     * same condition — nobody has cited them in a ruling, so nothing becomes uncheckable by their going.
     *
     * <p>An empty {@code refs} is a no-op rather than a truncation. The rolling-control arm of metric
     * drift carries no baseline rows at all (its reference is a merge of per-day histograms, not a
     * window), and letting it through would delete a pinned arm's baseline evidence on the first
     * control-arm re-fire.
     */
    public int replace(String projectId, String findingId, String role, List<Ref> refs, String now) {
        if (refs.isEmpty()) return 0;
        jdbc.sql("DELETE FROM finding_evidence WHERE project_id = :pid AND finding_id = :fid AND role = :role")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("role", role)
                .update();
        // Zeroed rather than left to drift: `record` ACCUMULATES onto this tally, so a stale count would
        // survive the delete and report a population larger than the rows behind it. A reader uses the
        // tally to tell "I hold the enumerated population" from "the write was interrupted", and that
        // distinction is the whole reason the number is stored beside the rows.
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
     * The finding's whole evidence set, role then rank — the order the detector wrote it in.
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

    /** A page of a finding's evidence and the cursor that resumes after it — null when this was the last. */
    public record Page(
            List<FindingEvidenceRow> rows, @Nullable String nextCursor) {}

    /**
     * One page of a finding's evidence in the detector's own order, optionally narrowed to a role.
     *
     * <p><b>The keyset is {@code (role, rank, id)}, not {@code rank}.</b> Rank is the detector's order
     * within a role and it is neither dense nor unique across the set: {@link #record} assigns it
     * positionally across a batch, so a re-record leaves gaps where rows conflicted, and two roles number
     * from zero independently. Paging on rank alone would skip rows at every gap and interleave roles on
     * an unfiltered page. The id tiebreak is what makes the order total.
     *
     * <p>A null rank sorts to the tail ({@code ORDER BY … rank} is NULLS LAST, matching
     * {@code ix_finding_evidence_finding}), and the cursor comparison coalesces it to {@link #RANK_TAIL}
     * so those rows stay reachable on the pages after the first rather than silently dropping out of the
     * set — which on an evidence door would be the worst kind of wrong answer: a short population that
     * still looks complete.
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
     * One evidence ref with the span it points at — what the finding page's evidence table renders.
     *
     * <p>Span-shaped, not trace-shaped, because a {@code member} IS a span: tool error enumerates one ref
     * per CALL, so several refs routinely name the same trace and a trace-shaped row would repeat itself
     * with identical cells. {@code tokens} and {@code cost} are null on a tool span rather than missing
     * — they are an LLM span's properties — and the table simply leaves those cells empty.
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
             * returned, or null where the payload was never written or has aged out.
             *
             * <p>Truncated in SQL rather than in Java: a single LLM span's output runs to megabytes,
             * and a page of eight of them shipped whole would be the largest response this API sends
             * to render a table cell that is one line tall.
             */
            @Nullable String inputPreview,
            @Nullable String outputPreview) {}

    /** A page of {@link SpanRef}s and the cursor that resumes after it — null when this was the last. */
    public record SpanPage(List<SpanRef> rows, @Nullable String nextCursor) {}

    /**
     * {@link #page}, with the span each ref names joined on.
     *
     * <p>Same keyset, same order, same over-fetch — the ONLY difference is the join, so a reader paging
     * this and a reader paging the refs see the population in the same sequence. Kept separate from
     * {@link #page} rather than folded into it because the MCP door deliberately returns ids and no
     * bodies: an agent asks for refs and then decides what to open, while a person looking at a table
     * needs the row to say something. Two readers, two shapes.
     *
     * <p>The join is LEFT and falls back to the trace's logical root for a trace-grain ref, so a
     * behaviour-drift finding (which enumerates traces, not spans) still renders a row with a name and a
     * time on it rather than an id and four dashes. A ref whose substrate has aged out keeps its ids and
     * carries nulls, which is the honest rendering of "this was measured, and it is gone now".
     *
     * <h2>Why this one joins {@code span_payload}</h2>
     *
     * <p>Spec rule 5 keeps payloads off list and sweep surfaces, and {@code SubstrateReadRepository}
     * states the terms of the exception: the join must be BOUNDED — a keyset page of at most
     * {@code limit} spans — and 1:1 on the span primary key. Both hold here, and the columns are
     * truncated in SQL so a page costs {@code limit × 2 × PREVIEW_CHARS} however large the payloads
     * behind it are. What it buys is the only column on this table that says what actually happened:
     * a list of failing calls whose cells are all the same tool, the same status and the same trace
     * describes nothing a reader could not already see in the headline.
     *
     * <p>It joins the RESOLVED span rather than {@code e.span_id}, so a trace-grain ref picks up its
     * logical root's payload for the same reason it picks up that root's name.
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
        // Seeded from the last row of THIS page, exactly as page() does — the over-fetched row is the
        // first row of the NEXT page, and seeding from it would skip it.
        Seeded last = rows.get(limit - 1);
        return new SpanPage(
                List.copyOf(refs.subList(0, limit)),
                encodeCursor(last.ref().role(), last.ref().rank(), last.evidenceId()));
    }

    /**
     * How many refs of each role SURVIVE, counted now — every role in the vocabulary, zeros included.
     *
     * <p>Deliberately not {@code finding.evidence_counts}, which is the count as WRITTEN. Refs age out
     * with the substrate they point at, so this number comes in at or below the stored one, and the pair
     * is what separates "the detector had nothing to enumerate on that side" from "the substrate it
     * pointed at is gone" from "the write was interrupted". A caller that wants the stored number reads
     * {@link FindingRow#evidenceCount}; a caller that wants to know what it can actually open reads this.
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
     * it — Layer 2 refuses to rule on a finding it cannot read, and this is where that shows up.
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
     * whatever role wrote it. Empty only when the finding cites no trace at all — nothing was ever
     * written, or everything it pointed at has aged out.
     *
     * <p><b>Role-agnostic on purpose.</b> Every caller here used to ask {@link #exemplarTraceId}, and
     * that broke the moment {@code exemplar} stopped being universal: tool error cites {@code witness}
     * and {@code member}, metric drift cites {@code member} and {@code baseline}, so demanding an
     * exemplar refused analysis on precisely the findings the role redesign was for.
     *
     * <p><b>The anchor is not the agent's entry point.</b> No dossier file carries a trace id and the
     * agent pages the evidence through MCP, deciding for itself what to read. This is what the job
     * resolves a session and a deploy from — which is why any cited trace will serve, and why the
     * significance order still matters: a {@code baseline} trace would name the HEALTHY side's deploy.
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
     * The traces a set of findings recorded, most significant first — the case page's exemplar list and
     * the grader lane's input, read in one query so a page of cases is not N of them.
     *
     * <p><b>"Most significant" is an explicit order, not the role column's.</b> Ordering by {@code role}
     * sorts the vocabulary alphabetically, which puts {@code baseline} — the healthy side, the traces the
     * classifier compared AGAINST — ahead of the exemplars, and {@code changepoint} ahead of them too.
     * Every caller here truncates: the case page shows a handful and {@link
     * ai.tessary.classifier.grader.GraderRunWorker#MAX_TRACES} grades five. Alphabetical order
     * would spend that budget on the wrong side of the comparison and report that the graders found
     * nothing wrong, which is true of a baseline and says nothing about the finding.
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
     * The evidence sets of many findings at once, keyed by finding id — what a page of findings needs so
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
     * <p>Null covers every unusable token — absent, corrupt, a foreign generation — because there is
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

    /** The keyset, from its three parts — shared so the ref page and the span page tokenize identically. */
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

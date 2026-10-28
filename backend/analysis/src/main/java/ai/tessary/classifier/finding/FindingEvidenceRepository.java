// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.ingest.PreviewCursor;
import ai.tessary.redaction.CredentialMasking;
import ai.tessary.tenant.Ids;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The evidence behind a finding: append-only, uncapped, written in the transaction that opens the finding. The whole
 * population is written, not a sample, so a claim can be audited; retention treats the rows as pinned. {@link #record}
 * bumps {@code finding.evidence_counts} in the same call, and {@code ux_finding_evidence_ref} makes repeats a no-op.
 */
@Repository
public class FindingEvidenceRepository {

    /** Not a cap: a statement carries at most 65535 binds, and each row binds five. */
    private static final int INSERT_CHUNK = 2000;

    private static final String COLS =
            "id, project_id, finding_id, session_id, trace_id, span_id, role, rank, created_at";

    /** ASC is NULLS LAST, the order {@code ix_finding_evidence_finding} stores, so scan and sort agree. */
    private static final String ORDER = "role, rank NULLS LAST, id";

    /** Only for the cursor comparison; {@link #record} always assigns a rank. */
    private static final int RANK_TAIL = Integer.MAX_VALUE;

    /** Enough to recognise the call and its error. Cut with SQL {@code left()} so the bytes never leave Postgres. */
    private static final int PREVIEW_CHARS = 200;

    /** Both turn counts stop here, so a long session costs no more than this many index entries per row. */
    static final int POSITION_CAP = 10_000;

    /** The same columns on span and trace; a whole-run row reads the trace's rollup, a step its span. */
    private static final List<String> TOKEN_BUCKETS =
            List.of("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "reasoning_tokens");

    /**
     * A turn is a top-level trace of the session. Each count stops at {@link #POSITION_CAP} through its inner
     * {@code LIMIT}; {@code ix_trace_session} serves both counts and the {@code (started_at, id)} comparison.
     */
    private static final String TURNS = " FROM trace x WHERE x.project_id = e.project_id AND x.session_id = ss.id"
            + " AND x.parent_trace_id IS NULL AND NOT x.is_deleted";

    private static final String POSITION_JOIN = " LEFT JOIN LATERAL ("
            + "   SELECT (SELECT count(*) FROM (SELECT 1" + TURNS + " LIMIT " + POSITION_CAP + ") c) AS session_traces,"
            + "          CASE WHEN t.id IS NOT NULL AND t.parent_trace_id IS NULL THEN"
            + "            (SELECT count(*) FROM (SELECT 1" + TURNS
            + "               AND (x.started_at, x.id) <= (t.started_at, t.id) LIMIT " + POSITION_CAP + ") c)"
            + "          END AS trace_index"
            + "    WHERE ss.id IS NOT NULL) pos ON true";

    /**
     * Deploy, host, instance and model identity, small enough to ride every row. No key that carries a prompt or a
     * completion; {@code gen_ai.request.model} already rides {@code models}.
     */
    public static final List<String> SHORT_ATTRIBUTE_KEYS = List.of(
            "service.name",
            "service.version",
            "service.instance.id",
            "deployment.environment.name",
            "vcs.ref.head.revision",
            "host.name",
            "k8s.pod.name",
            "k8s.node.name",
            "container.id",
            "cloud.region",
            "cloud.availability_zone",
            "gen_ai.response.model",
            "gen_ai.provider.name",
            "gen_ai.system");

    /** Rows read per query by {@link #exportPage}; not a page size. */
    private static final int EXPORT_CHUNK = 200;

    /** Charged per exported row on top of its text, for the measured fields that ride with it. */
    private static final int EXPORT_ROW_OVERHEAD = 512;

    private static final char CURSOR_SEP = '\u001f';

    /** Any other token shape restarts at the first page rather than mis-resuming. */
    private static final String CURSOR_VERSION = "v1";

    private final JdbcClient jdbc;

    public FindingEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Ref(
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId) {

        public static Ref trace(String traceId) {
            return new Ref(null, traceId, null);
        }

        /** Both halves of the composite span key, or it resolves to nothing. */
        public static Ref span(String traceId, String spanId) {
            return new Ref(null, traceId, spanId);
        }

        public static Ref session(String sessionId) {
            return new Ref(sessionId, null, null);
        }
    }

    /**
     * Repeats are skipped, so this is safe on every pass. {@code rank} continues past what is stored, so a re-record
     * leaves gaps rather than renumbering.
     */
    public int record(String projectId, String findingId, String role, List<Ref> refs, String now) {
        if (refs.isEmpty()) return 0;
        // Distinct-preserving: the same trace twice must not spend two ranks.
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
     * Only for {@link FindingEvidenceRow.Role#WITNESS}, which promises no enumeration: a cause that keeps firing must
     * not pin ever more retention. A ref already stored still spends a slot.
     */
    public int recordUpTo(String projectId, String findingId, String role, List<Ref> refs, int max, String now) {
        int remaining = max - countFor(findingId, role);
        if (remaining <= 0 || refs.isEmpty()) return 0;
        return record(projectId, findingId, role, refs.size() <= remaining ? refs : refs.subList(0, remaining), now);
    }

    /** Does not touch {@code updated_at}: recording what a claim rested on does not change the claim. */
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
     * The one exception to append-never-rewrite: callers must first establish that no ruling stands on the finding
     * ({@code FindingRepository.Recorded#ruled()}). An empty {@code refs} is a no-op, since some references
     * legitimately have no rows.
     */
    public int replace(String projectId, String findingId, String role, List<Ref> refs, String now) {
        if (refs.isEmpty()) return 0;
        jdbc.sql("DELETE FROM finding_evidence WHERE project_id = :pid AND finding_id = :fid AND role = :role")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("role", role)
                .update();
        // Zeroed, or `record` would accumulate onto a stale count after the delete.
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

    /** Unpaged; a reader that does not need six figures at once takes {@link #spanPage} or {@link #claimFirst}. */
    public List<FindingEvidenceRow> listByFinding(String projectId, String findingId) {
        return jdbc.sql("SELECT " + COLS + " FROM finding_evidence"
                        + " WHERE project_id = :pid AND finding_id = :fid"
                        + " ORDER BY " + ORDER)
                .param("pid", projectId)
                .param("fid", findingId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public record Head(List<FindingEvidenceRow> rows, boolean more) {}

    /**
     * Witnesses, then baseline, then the rest, so healthy members cannot crowd failing rows out of a bounded read. One
     * index-ordered read per tier, over-fetching one to detect more.
     */
    public Head claimFirst(String projectId, String findingId, int limit) {
        String where = " FROM finding_evidence WHERE project_id = :pid AND finding_id = :fid AND ";
        String tail = " ORDER BY " + ORDER + " LIMIT :n)";
        List<FindingEvidenceRow> rows = jdbc.sql("(SELECT " + COLS + ", 0 AS tier" + where + "role = :witness" + tail
                        + " UNION ALL (SELECT " + COLS + ", 1 AS tier" + where + "role = :baseline" + tail
                        + " UNION ALL (SELECT " + COLS + ", 2 AS tier" + where + "role NOT IN (:witness, :baseline)"
                        + tail
                        + " ORDER BY tier, " + ORDER + " LIMIT :n")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("witness", FindingEvidenceRow.Role.WITNESS)
                .param("baseline", FindingEvidenceRow.Role.BASELINE)
                .param("n", limit + 1)
                .query((rs, n) -> map(rs))
                .list();
        if (rows.size() <= limit) return new Head(rows, false);
        return new Head(List.copyOf(rows.subList(0, limit)), true);
    }

    /** Span-shaped: tool error writes one ref per call, so several refs routinely name the same trace. */
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
            /**
             * The span's own total on a single-step row; the trace rollup on a whole-run row ({@link #spanId} null).
             */
            @Nullable Long totalTokens,
            @Nullable Double totalCost,
            List<String> models,
            /** True on a whole-run row whose trace has not rolled up yet. */
            boolean notRolledUp,
            /** {@link #totalCost} is then a floor. */
            boolean partialCost,
            /** Late spans can still change the totals. */
            boolean staleTotals,
            @Nullable String callSiteId,
            @Nullable String projectVersionId,
            @Nullable String userId,
            /** Like {@link #totalTokens}: the span's own on a single-step row, the trace rollup on a whole-run row. */
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            /**
             * The trace's 1-based turn in its session, capped at {@link FindingEvidenceRepository#POSITION_CAP}; null
             * on a session row, a trace with no session and a sub-agent trace, which is not a turn.
             */
            @Nullable Integer traceIndex,
            /** The session's turn count, capped likewise, so the cap reads as "at least". */
            @Nullable Integer sessionTraces,
            @Nullable Long inputBytes,
            @Nullable Long outputBytes,
            /** The payload row exists, even with null text; false once retention purged it. */
            boolean payloadAvailable,
            /**
             * Only the keys of {@link FindingEvidenceRepository#SHORT_ATTRIBUTE_KEYS} the payload carries, masked;
             * never null.
             */
            Map<String, String> attributes,
            /**
             * Truncated in SQL, since one LLM output can be megabytes; masked by
             * {@link ai.tessary.redaction.CredentialMasking#mask}, since a project with redaction off can store a raw
             * key.
             */
            @Nullable String inputPreview,
            @Nullable String outputPreview,
            /** Null unless {@link #spanPage} was asked for a secret-leak facet. */
            @Nullable String secretKey,
            @Nullable String storedAs,
            /** Null unless {@link #spanPage} was asked for a malformed-output facet. */
            @Nullable String violation) {}

    public record SpanPage(List<SpanRef> rows, @Nullable String nextCursor) {}

    /**
     * Keyset on {@code (role, rank, id)}: rank is neither dense nor unique, and a null rank coalesces to
     * {@link #RANK_TAIL} so it stays reachable. A trace-grain ref joins LATERAL to one outermost root, since
     * {@code is_logical_root} marks every sub-agent boundary and a plain join multiplied rows. Previews are cut in SQL.
     * The leak and violation joins run only on that finding's own page.
     */
    public SpanPage spanPage(
            String projectId,
            String findingId,
            @Nullable String role,
            int limit,
            @Nullable String cursor,
            boolean secretLeak,
            boolean malformedOutput) {
        record Seeded(SpanRef ref, String evidenceId) {}
        List<Seeded> rows = evidenceRows(
                projectId,
                findingId,
                role,
                limit + 1,
                decodeCursor(cursor),
                secretLeak,
                malformedOutput,
                List.of(),
                (rs, n) -> new Seeded(mapSpan(rs), rs.getString("evidence_id")));
        List<SpanRef> refs = rows.stream().map(Seeded::ref).toList();
        if (refs.size() <= limit) return new SpanPage(refs, null);
        // Seeded from THIS page's last row; the over-fetched row starts the next page.
        Seeded last = rows.get(limit - 1);
        return new SpanPage(
                List.copyOf(refs.subList(0, limit)),
                encodeCursor(last.ref().role(), last.ref().rank(), last.evidenceId()));
    }

    /** {@link #spanPage}'s one SELECT, with {@code extraColumns} appended for a reader that needs more of the payload. */
    private <T> List<T> evidenceRows(
            String projectId,
            String findingId,
            @Nullable String role,
            int fetch,
            @Nullable Key key,
            boolean secretLeak,
            boolean malformedOutput,
            List<String> extraColumns,
            RowMapper<T> mapper) {
        StringBuilder sql = new StringBuilder("SELECT e.id AS evidence_id, e.role, e.rank, e.session_id,"
                + " e.trace_id, e.span_id,"
                + " s.name, s.kind, s.status, s.level, s.error_type,"
                + " COALESCE(s.started_at, t.started_at, ss.started_at) AS started_at, s.latency_ms,"
                + " CASE WHEN e.span_id IS NULL THEN t.total_tokens ELSE s.total_tokens END AS total_tokens,"
                + " CASE WHEN e.span_id IS NULL THEN t.total_cost ELSE s.total_cost END AS total_cost,"
                + " m.models,"
                + " (e.span_id IS NULL AND t.rolled_up_at IS NULL) AS not_rolled_up,"
                + " (e.span_id IS NULL AND COALESCE(t.unpriced_spans, 0) > 0) AS partial_cost,"
                + " (e.span_id IS NULL AND COALESCE(t.is_settled, false) = false) AS stale_totals,"
                + " CASE WHEN e.span_id IS NULL THEN t.call_site_id ELSE s.call_site_id END AS call_site_id,"
                + TOKEN_BUCKETS.stream()
                        .map(b -> " CASE WHEN e.span_id IS NULL THEN t." + b + " ELSE s." + b + " END AS " + b + ",")
                        .collect(Collectors.joining())
                + " COALESCE(s.project_version_id, t.project_version_id) AS project_version_id,"
                + " COALESCE(s.user_id, t.user_id, ss.user_id) AS user_id,"
                + " octet_length(pl.input)::bigint AS input_bytes,"
                + " octet_length(pl.output)::bigint AS output_bytes,"
                + " (pl.span_id IS NOT NULL) AS payload_available,"
                + " pos.session_traces, pos.trace_index,"
                + " attrs.keys AS attribute_keys, attrs.vals AS attribute_values,"
                + " left(pl.input, " + PREVIEW_CHARS + ") AS input_preview,"
                + " left(pl.output, " + PREVIEW_CHARS + ") AS output_preview,"
                + (secretLeak
                        ? " ld.evidence ->> 'masked' AS secret_key, ld.evidence ->> 'stored' AS stored_as"
                        : " NULL::text AS secret_key, NULL::text AS stored_as")
                + (malformedOutput
                        ? " , CASE WHEN md.evidence ->> 'reason' = 'not_json' THEN 'not JSON'"
                                + "        ELSE md.evidence -> 'violations' -> 0 ->> 'message' END AS violation"
                        : " , NULL::text AS violation")
                + extraColumns.stream().map(c -> ", " + c).collect(Collectors.joining())
                + " FROM finding_evidence e"
                + " LEFT JOIN LATERAL ("
                + "   SELECT sp.* FROM span sp"
                + "    WHERE sp.project_id = e.project_id AND sp.trace_id = e.trace_id"
                + "      AND (sp.id = e.span_id OR (e.span_id IS NULL AND sp.is_logical_root))"
                + "    ORDER BY (sp.id = e.span_id) DESC NULLS LAST,"
                + "             (sp.parent_span_id IS NULL) DESC,"
                + "             sp.depth ASC NULLS LAST,"
                + "             sp.started_at ASC NULLS LAST,"
                + "             sp.id"
                + "    LIMIT 1) s ON true"
                + " LEFT JOIN span_payload pl ON pl.project_id = s.project_id"
                + "   AND pl.trace_id = s.trace_id AND pl.span_id = s.id"
                + " LEFT JOIN trace t ON t.project_id = e.project_id AND t.id = e.trace_id AND t.is_deleted = false"
                + " LEFT JOIN LATERAL ("
                + "   SELECT array_agg(DISTINCT sp2.provided_model_name ORDER BY sp2.provided_model_name)"
                + "          FILTER (WHERE sp2.provided_model_name IS NOT NULL) AS models"
                + "     FROM span sp2"
                + "    WHERE sp2.project_id = e.project_id AND sp2.trace_id = e.trace_id AND sp2.is_deleted = false"
                + "      AND (e.span_id IS NULL OR sp2.id = e.span_id)) m ON true"
                + " LEFT JOIN session ss ON ss.project_id = e.project_id"
                + "   AND ss.id = COALESCE(e.session_id, t.session_id) AND NOT ss.is_deleted"
                + POSITION_JOIN
                + " LEFT JOIN LATERAL ("
                + "   SELECT array_agg(a.key ORDER BY a.key) AS keys, array_agg(a.value ORDER BY a.key) AS vals"
                + "     FROM jsonb_each_text(pl.attributes) a WHERE a.key IN (:attrKeys)) attrs ON true"
                + (secretLeak
                        ? " LEFT JOIN LATERAL (SELECT sld.evidence FROM secret_leak_detection sld"
                                + "   WHERE sld.project_id = e.project_id AND sld.subject_trace_id = e.trace_id"
                                + "     AND sld.subject_span_id = e.span_id"
                                + "   ORDER BY sld.created_at DESC LIMIT 1) ld ON true"
                        : "")
                + (malformedOutput
                        ? " LEFT JOIN LATERAL (SELECT mod.evidence FROM malformed_output_detection mod"
                                + "   WHERE mod.project_id = e.project_id AND mod.subject_trace_id = e.trace_id"
                                + "     AND mod.subject_span_id = e.span_id"
                                + "   ORDER BY mod.created_at DESC LIMIT 1) md ON true"
                        : "")
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
                .param("attrKeys", SHORT_ATTRIBUTE_KEYS)
                .param("n", fetch);
        if (role != null) spec = spec.param("role", role);
        if (key != null) {
            spec = spec.param("cRole", key.role()).param("cRank", key.rank()).param("cId", key.id());
        }
        return spec.query(mapper).list();
    }

    public record ExportRow(
            SpanRef measured,
            @Nullable String input,
            @Nullable String output,
            @Nullable Map<String, Object> attributes) {}

    public record ExportPage(List<ExportRow> rows, @Nullable String nextCursor) {}

    /**
     * @param witnessCharge charged to the budget per {@code witness} row for text the caller attaches after the page
     *     closes, so the page still keeps to its bound
     */
    public record ExportLimits(int textChars, int maxRows, long textBudget, long witnessCharge) {

        public ExportLimits(int textChars, int maxRows, long textBudget) {
            this(textChars, maxRows, textBudget, 0);
        }
    }

    /**
     * {@link #spanPage}'s rows and cursor, plus the payload text cut in SQL at {@code textChars +}
     * {@link EvidenceExportText#MARGIN} and masked before the final cut. Read in chunks; a page closes on the row that
     * reaches {@code maxRows} or the text budget, so it always holds at least one row, and the cursor is seeded from
     * that row so the next page starts on the first row not included.
     */
    public ExportPage exportPage(
            String projectId,
            String findingId,
            @Nullable String role,
            ExportLimits limits,
            @Nullable String cursor,
            boolean secretLeak,
            boolean malformedOutput) {
        int cut = limits.textChars() + EvidenceExportText.MARGIN;
        List<String> text = List.of(
                "left(pl.input, " + cut + ") AS input_text",
                "left(pl.output, " + cut + ") AS output_text",
                "pl.attributes::text AS attributes_json");
        record Raw(
                SpanRef ref,
                String evidenceId,
                @Nullable String input,
                @Nullable String output,
                @Nullable String attributes) {}
        Key key = decodeCursor(cursor);
        List<ExportRow> rows = new ArrayList<>();
        long spent = 0;
        while (true) {
            List<Raw> chunk = evidenceRows(
                    projectId,
                    findingId,
                    role,
                    EXPORT_CHUNK + 1,
                    key,
                    secretLeak,
                    malformedOutput,
                    text,
                    (rs, n) -> new Raw(
                            mapSpan(rs),
                            rs.getString("evidence_id"),
                            rs.getString("input_text"),
                            rs.getString("output_text"),
                            rs.getString("attributes_json")));
            int usable = Math.min(chunk.size(), EXPORT_CHUNK);
            for (int i = 0; i < usable; i++) {
                Raw raw = chunk.get(i);
                ExportRow row = new ExportRow(
                        raw.ref(),
                        EvidenceExportText.maskThenCut(raw.input(), limits.textChars()),
                        EvidenceExportText.maskThenCut(raw.output(), limits.textChars()),
                        EvidenceExportText.attributes(raw.attributes(), limits.textChars()));
                rows.add(row);
                spent += EvidenceExportText.chars(row.input())
                        + EvidenceExportText.chars(row.output())
                        + EvidenceExportText.chars(row.attributes())
                        + EXPORT_ROW_OVERHEAD
                        + (FindingEvidenceRow.Role.WITNESS.equals(raw.ref().role()) ? limits.witnessCharge() : 0);
                if (rows.size() >= limits.maxRows() || spent >= limits.textBudget()) {
                    boolean more = i + 1 < chunk.size();
                    return new ExportPage(
                            rows,
                            more ? encodeCursor(raw.ref().role(), raw.ref().rank(), raw.evidenceId()) : null);
                }
            }
            if (chunk.size() <= EXPORT_CHUNK) return new ExportPage(rows, null);
            Raw last = chunk.get(EXPORT_CHUNK - 1);
            Integer lastRank = last.ref().rank();
            key = new Key(last.ref().role(), lastRank == null ? RANK_TAIL : lastRank, last.evidenceId());
        }
    }

    /** Counted now, so at or below {@code finding.evidence_counts} once refs age out. */
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

    /** A finding with no surviving evidence is absent, a real state the caller renders. */
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

    private record Key(String role, int rank, String id) {}

    /** Null for every unusable token: the first page is a correct answer to all of them. */
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

    private static String encodeCursor(String role, @Nullable Integer rank, String id) {
        return PreviewCursor.encode(
                CURSOR_VERSION + CURSOR_SEP + role + CURSOR_SEP + (rank == null ? RANK_TAIL : rank) + CURSOR_SEP + id,
                0);
    }

    private static SpanRef mapSpan(ResultSet rs) throws SQLException {
        Array modelsArray = rs.getArray("models"); // freed below; Array has no close()
        List<String> models;
        if (modelsArray == null) {
            models = List.of();
        } else {
            try {
                models = List.of((String[]) modelsArray.getArray());
            } finally {
                modelsArray.free();
            }
        }
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
                // rs.getString on a timestamptz gives Postgres's own rendering, which clients parse inconsistently.
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
                models,
                rs.getBoolean("not_rolled_up"),
                rs.getBoolean("partial_cost"),
                rs.getBoolean("stale_totals"),
                rs.getString("call_site_id"),
                rs.getString("project_version_id"),
                rs.getString("user_id"),
                (Long) rs.getObject("input_tokens"),
                (Long) rs.getObject("output_tokens"),
                (Long) rs.getObject("cache_read_tokens"),
                (Long) rs.getObject("cache_write_tokens"),
                (Long) rs.getObject("reasoning_tokens"),
                count(rs, "trace_index"),
                count(rs, "session_traces"),
                (Long) rs.getObject("input_bytes"),
                (Long) rs.getObject("output_bytes"),
                rs.getBoolean("payload_available"),
                attributes(rs),
                CredentialMasking.mask(rs.getString("input_preview")),
                CredentialMasking.mask(rs.getString("output_preview")),
                rs.getString("secret_key"),
                rs.getString("stored_as"),
                rs.getString("violation"));
    }

    /** {@code count(*)} is a bigint; the cap keeps it inside an int. */
    private static @Nullable Integer count(ResultSet rs, String column) throws SQLException {
        Long n = (Long) rs.getObject(column);
        return n == null ? null : n.intValue();
    }

    /** The two arrays are aggregated in one key order, so they zip; each value is masked, a JSON null left out. */
    private static Map<String, String> attributes(ResultSet rs) throws SQLException {
        Array keysArray = rs.getArray("attribute_keys");
        Array valuesArray = rs.getArray("attribute_values");
        try {
            if (keysArray == null || valuesArray == null) return Map.of();
            String[] keys = (String[]) keysArray.getArray();
            String[] values = (String[]) valuesArray.getArray();
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) {
                String value = CredentialMasking.mask(values[i]);
                if (value != null) out.put(keys[i], value);
            }
            return Collections.unmodifiableMap(out);
        } finally {
            if (keysArray != null) keysArray.free();
            if (valuesArray != null) valuesArray.free();
        }
    }

    private int countFor(String findingId, String role) {
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

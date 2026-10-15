// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import ai.tessary.classifier.detector.GroundingEvidenceReads;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The substrate read surface for the classifier sweeps. Spans carry their own correlation columns, so nothing climbs to
 * trace or session. Detector projections are the one bounded payload join on a read path; aged-out text is null, not
 * empty. The cursor rides as {@code "<trace_id>:<span_id>"}, and {@link #parseHandle} degrades an unreadable one to "no
 * cursor" so a sweep converges instead of sticking.
 */
@Repository
public class SubstrateReadRepository implements CallSiteSchemaReads, CallSiteShapeReads, GroundingEvidenceReads {

    /** One enormous retrieved document would otherwise crowd out every other piece of evidence. */
    private static final int EVIDENCE_CHARS_PER_ROW = 4_000;

    /**
     * Taken best-rank-first: the model reads an 8192-token window and cuts the end of its prompt, so what falls outside
     * is what the retriever ranked lowest.
     */
    private static final int EVIDENCE_ROWS = 6;

    private final JdbcClient jdbc;

    public SubstrateReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The onboarding liveness poll; {@code EXISTS} short-circuits rather than counting. */
    public boolean hasSpans(String projectId) {
        Boolean live = jdbc.sql("SELECT EXISTS(SELECT 1 FROM span WHERE project_id = :pid)")
                .param("pid", projectId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(live);
    }

    /**
     * Bucketed on event time in UTC, so a backfilled corpus lands on its own days. Served by
     * {@code ix_trace_project_started_id}.
     */
    public List<DailyTraceCount> dailyTraceCounts(String projectId, java.time.Instant from) {
        return jdbc.sql("""
                SELECT (started_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS total
                FROM trace
                WHERE project_id = :pid AND started_at >= :from AND is_deleted IS NOT TRUE
                GROUP BY day
                ORDER BY day ASC
                """)
                .param("pid", projectId)
                .param("from", java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .query((rs, n) ->
                        new DailyTraceCount(rs.getObject("day", java.time.LocalDate.class), rs.getLong("total")))
                .list();
    }

    public record DailyTraceCount(java.time.LocalDate day, long total) {}

    /**
     * Spans with no {@code call_site_id}, for the "instrument your call sites" nudge. Capped at {@value #UNTAGGED_CAP}
     * because the UI shows "{cap}+" beyond it. Served by {@code ix_span_untagged}, which holds only untagged spans.
     */
    public long untaggedSpans(String projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM (
                    SELECT 1 FROM span
                    WHERE project_id = :pid AND call_site_id IS NULL
                    LIMIT :cap
                ) capped
                """)
                .param("pid", projectId)
                .param("cap", UNTAGGED_CAP)
                .query(Long.class)
                .single();
    }

    public static final int UNTAGGED_CAP = 1000;

    /**
     * Served by the partial index {@code ix_span_call_site}. Stricter than
     * {@link ai.tessary.onboarding.OnboardingRepository#trafficWindow}, which counts untagged spans too.
     */
    public boolean hasTaggedSpan(String projectId) {
        Boolean tagged = jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM span WHERE project_id = :pid AND call_site_id IS NOT NULL)")
                .param("pid", projectId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(tagged);
    }

    /** Capped like {@link #untaggedSpans}. */
    public long spansReceived(String projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM (SELECT 1 FROM span WHERE project_id = :pid LIMIT :cap) capped")
                .param("pid", projectId)
                .param("cap", UNTAGGED_CAP)
                .query(Long.class)
                .single();
    }

    public long taggedSpans(String projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM (
                    SELECT 1 FROM span
                    WHERE project_id = :pid AND call_site_id IS NOT NULL
                    LIMIT :cap
                ) capped
                """)
                .param("pid", projectId)
                .param("cap", UNTAGGED_CAP)
                .query(Long.class)
                .single();
    }

    /** Served by {@code ix_span_project_started}'s descending end. */
    public @Nullable String lastSpanAt(String projectId) {
        return jdbc.sql("""
                SELECT to_char(MAX(started_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                FROM span WHERE project_id = :pid
                """)
                .param("pid", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * A single most-recent-row read, not a majority vote: the service name only lives in
     * {@code span_payload.attributes}, and a project-wide payload scan is what Rule 5 forbids.
     */
    public @Nullable String recentServiceName(String projectId) {
        return jdbc.sql("""
                SELECT pl.attributes ->> 'service.name'
                FROM span s
                JOIN span_payload pl
                  ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id
                WHERE s.project_id = :pid AND pl.attributes ->> 'service.name' IS NOT NULL
                ORDER BY s.started_at DESC
                LIMIT 1
                """)
                .param("pid", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** The any-span rule the traces list filters by. A trace with no tagged span is absent. */
    public java.util.Map<String, java.util.Set<String>> callSitesByTrace(
            String projectId, java.util.Set<String> traceIds) {
        if (traceIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        jdbc.sql("SELECT DISTINCT trace_id, call_site_id FROM span"
                        + " WHERE project_id = :pid AND trace_id = ANY(:ids) AND call_site_id IS NOT NULL")
                .param("pid", projectId)
                .param("ids", traceIds.toArray(String[]::new))
                .query((rs, n) -> out.computeIfAbsent(rs.getString("trace_id"), t -> new java.util.HashSet<>())
                        .add(rs.getString("call_site_id")))
                .list();
        return java.util.Map.copyOf(out);
    }

    /**
     * One index probe per call site via {@code ix_trace_scope_settled_started}. The {@code COALESCE} is spelled exactly
     * as the index spells it, or the planner cannot match it.
     */
    public java.util.SortedSet<String> knownCallSiteIds(String projectId) {
        java.util.SortedSet<String> out = new java.util.TreeSet<>(
                jdbc.sql("""
                WITH RECURSIVE seen(k) AS (
                    SELECT MIN(COALESCE(call_site_id, '__unattributed__')) FROM trace
                     WHERE project_id = :pid AND is_settled AND is_deleted IS NOT TRUE
                    UNION ALL
                    SELECT (SELECT MIN(COALESCE(t.call_site_id, '__unattributed__')) FROM trace t
                             WHERE t.project_id = :pid AND t.is_settled AND t.is_deleted IS NOT TRUE
                               AND COALESCE(t.call_site_id, '__unattributed__') > seen.k)
                      FROM seen WHERE seen.k IS NOT NULL
                )
                SELECT k FROM seen WHERE k IS NOT NULL AND k <> '__unattributed__'
                UNION
                SELECT id FROM call_site WHERE project_id = :pid
                """).param("pid", projectId).query(String.class).list());
        return java.util.Collections.unmodifiableSortedSet(out);
    }

    @Override
    public java.util.Map<String, String> callSiteOutputSchemas(String projectId, java.util.Set<String> callSiteIds) {
        if (callSiteIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        jdbc.sql("SELECT id, output_schema FROM call_site "
                        + "WHERE project_id = :pid AND id IN (:ids) AND output_schema IS NOT NULL")
                .param("pid", projectId)
                .param("ids", callSiteIds)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("output_schema")))
                .list();
        return java.util.Map.copyOf(out);
    }

    /** None means Malformed Output cannot judge yet, which differs from judging and finding nothing. */
    public boolean anyOutputSchema(String projectId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM call_site WHERE project_id = :pid AND output_schema IS NOT NULL)")
                .param("pid", projectId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public java.util.Map<String, String> callSiteShapes(String projectId, java.util.Set<String> callSiteIds) {
        if (callSiteIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        jdbc.sql("SELECT id, shape FROM call_site " + "WHERE project_id = :pid AND id IN (:ids) AND shape IS NOT NULL")
                .param("pid", projectId)
                .param("ids", callSiteIds)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("shape")))
                .list();
        return java.util.Map.copyOf(out);
    }

    /**
     * Retrieved documents from the nearest prior trace in the conversation with retrievals (own trace first), keyed by
     * span id. Conversation scope because follow-ups reuse earlier retrievals (same-trace scope false-fired 88%).
     * Bounded to before the answer, since a later passage would mark a hallucination supported. Tool results are not
     * evidence (84/84 false positives); reranker candidates are dropped and duplicates de-duplicated.
     */
    @Override
    public java.util.Map<String, GroundingEvidenceReads.Evidence> groundingEvidence(
            String projectId, java.util.Set<GroundingEvidenceReads.SpanRef> spans) {
        if (spans.isEmpty()) return java.util.Map.of();
        List<String> traceIds = spans.stream()
                .map(GroundingEvidenceReads.SpanRef::traceId)
                .distinct()
                .toList();
        List<String> spanIds = spans.stream()
                .map(GroundingEvidenceReads.SpanRef::spanId)
                .distinct()
                .toList();
        java.util.Map<String, List<String>> acc = new java.util.HashMap<>();
        java.util.Set<String> reachedOutside = new java.util.HashSet<>();
        // Conversation scope, deliberately not time-bounded: reaching outside anywhere in the conversation reads BLIND
        // rather than GROUNDLESS. Only the evidence text below is time-bounded.
        jdbc.sql("""
                SELECT s.id AS span_id
                FROM span s
                  JOIN trace tr ON tr.project_id = s.project_id AND tr.id = s.trace_id
                WHERE s.project_id = :pid AND s.trace_id IN (:tids) AND s.id IN (:sids)
                  AND s.is_deleted IS NOT TRUE
                  AND EXISTS (SELECT 1 FROM span x
                               JOIN trace xtr ON xtr.project_id = x.project_id AND xtr.id = x.trace_id
                               WHERE x.project_id = s.project_id AND x.is_deleted IS NOT TRUE
                                 AND (
                                   xtr.session_id = tr.session_id
                                   OR (x.trace_id = s.trace_id AND tr.session_id IS NULL)
                                 )
                                 -- the tool-like kinds that count as external work; omitting one
                                 -- makes its traces read GROUNDLESS instead of BLIND
                                 AND x.kind IN ('tool','mcp','retrieval','reranker','embedding'))
                """)
                .param("pid", projectId)
                .param("tids", traceIds)
                .param("sids", spanIds)
                .query((rs, n) -> reachedOutside.add(rs.getString("span_id")))
                .list();
        // Nearest prior retrieval, not a conversation-wide blend, since the topic can shift across turns.
        jdbc.sql("""
                SELECT s.id AS span_id, e.txt AS txt, e.ord AS ord
                FROM span s
                  JOIN trace tr ON tr.project_id = s.project_id AND tr.id = s.trace_id
                  JOIN LATERAL (
                      SELECT rd2.trace_id AS trace_id
                        FROM retrieved_doc rd2
                        JOIN span r2 ON r2.project_id = rd2.project_id AND r2.trace_id = rd2.trace_id
                                     AND r2.id = rd2.span_id AND r2.is_deleted IS NOT TRUE
                        JOIN trace r2tr ON r2tr.project_id = r2.project_id AND r2tr.id = r2.trace_id
                       WHERE rd2.project_id = s.project_id AND rd2.is_deleted IS NOT TRUE
                         AND (
                           r2tr.session_id = tr.session_id
                           OR (r2.trace_id = s.trace_id AND tr.session_id IS NULL)
                         )
                         AND r2.started_at <= s.started_at
                       ORDER BY r2.started_at DESC
                       LIMIT 1
                  ) nearest ON TRUE
                  JOIN LATERAL (
                      SELECT d.txt, d.ord
                        FROM (
                          SELECT DISTINCT ON (rd.content)
                                 left(rd.content, :cap) AS txt,
                                 COALESCE(rd.rank, rd.seq, 0) AS ord
                            FROM retrieved_doc rd
                            JOIN span r
                              ON r.project_id = rd.project_id AND r.trace_id = rd.trace_id
                             AND r.id = rd.span_id AND r.is_deleted IS NOT TRUE
                           WHERE rd.project_id = s.project_id AND rd.trace_id = nearest.trace_id
                             AND rd.is_deleted IS NOT TRUE
                             AND COALESCE(rd.list_role, 'result') <> 'candidate'
                             AND rd.content IS NOT NULL AND rd.content <> ''
                             AND r.started_at <= s.started_at
                           ORDER BY rd.content, COALESCE(rd.rank, rd.seq, 0)
                        ) d
                       -- content breaks rank ties: two retrieval spans in one trace both rank from 0,
                       -- and an unstable sort would hand the same turn a different premise on rescore.
                       ORDER BY d.ord, d.txt
                       LIMIT :rows
                  ) e ON TRUE
                WHERE s.project_id = :pid AND s.trace_id IN (:tids) AND s.id IN (:sids)
                  AND s.is_deleted IS NOT TRUE
                ORDER BY s.id, e.ord, e.txt
                """)
                .param("pid", projectId)
                .param("tids", traceIds)
                .param("sids", spanIds)
                .param("cap", EVIDENCE_CHARS_PER_ROW)
                .param("rows", EVIDENCE_ROWS)
                .query((rs, n) -> {
                    String txt = rs.getString("txt");
                    if (txt != null && !txt.isBlank()) {
                        // One entry per retrieved row in rank order: the document boundary is part of what the
                        // groundedness head reads.
                        acc.computeIfAbsent(rs.getString("span_id"), k -> new java.util.ArrayList<>())
                                .add(txt.strip());
                    }
                    return Boolean.TRUE;
                })
                .list();
        java.util.Map<String, GroundingEvidenceReads.Evidence> out = new java.util.HashMap<>();
        for (GroundingEvidenceReads.SpanRef ref : spans) {
            String id = ref.spanId();
            List<String> docs = acc.getOrDefault(id, List.of());
            boolean outside = reachedOutside.contains(id);
            if (!docs.isEmpty() || outside) out.put(id, new GroundingEvidenceReads.Evidence(docs, outside));
        }
        return java.util.Map.copyOf(out);
    }

    public List<String> projectsWithObservations() {
        return jdbc.sql("SELECT DISTINCT project_id FROM span")
                .query((rs, n) -> rs.getString(1))
                .list();
    }

    /**
     * Strictly after the {@code (created_at, trace_id, id)} keyset; both id parts are needed because a span id is
     * unique only within its trace. An unparseable handle is treated as absent.
     */
    public List<SubstrateObservation> observationsAfter(
            String projectId, @Nullable String afterTs, @Nullable String afterHandle, int limit) {
        return spec(SELECT_SPAN, projectId, afterTs, afterHandle, limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    public record TurnCandidate(SubstrateObservation observation, boolean opensCallSiteTurn) {}

    /**
     * The same unfiltered window as {@link #observationsAfter}, with the candidate predicate projected rather than
     * filtered: an empty filtered window advances no cursor and would re-scan history forever. A candidate is the first
     * llm/agent span of its call site in a non-sub-agent trace.
     */
    public List<TurnCandidate> turnCandidatesAfter(
            String projectId, @Nullable String afterTs, @Nullable String afterHandle, int limit) {
        return spec(SELECT_TURN_CANDIDATE, projectId, afterTs, afterHandle, limit)
                .query((rs, n) -> new TurnCandidate(map(rs), rs.getBoolean("opens_call_site_turn")))
                .list();
    }

    /**
     * {@code count} is how many turns of the call site preceded the scored one, all of them; {@code spans} holds only
     * the last {@code turns}.
     */
    public record PriorTurns(List<SubstrateObservation> spans, int count) {
        public PriorTurns {
            spans = List.copyOf(spans);
        }
    }

    /**
     * A turn is a top-level trace of the scored trace's session that started before it and has an {@code llm} or
     * {@code agent} span of the call site, walked on {@code ix_trace_session}. A trace in no session has no earlier
     * turns: equality on a null key matches nothing.
     */
    public PriorTurns priorTurns(String projectId, String scoredTraceId, String callSiteId, int turns) {
        String earlier = """
                FROM trace f
                  JOIN trace t
                    ON t.project_id = f.project_id
                   AND t.parent_trace_id IS NULL
                   AND t.session_id = f.session_id
                   AND (t.started_at, t.id) < (f.started_at, f.id)
                WHERE f.project_id = :pid AND f.id = :scoredTraceId
                  AND EXISTS (SELECT 1 FROM span c
                               WHERE c.project_id = t.project_id AND c.trace_id = t.id
                                 AND c.call_site_id = :callSite AND c.kind IN ('llm', 'agent'))""";
        List<SubstrateObservation> spans = jdbc.sql(
                        "WITH turns AS MATERIALIZED (SELECT t.id " + earlier + """

                          ORDER BY t.started_at DESC, t.id DESC
                          LIMIT :turns)
                        """ + SPAN_COLUMNS + """

                        FROM turns tu
                          JOIN span s ON s.project_id = :pid AND s.trace_id = tu.id
                          LEFT JOIN span_payload pl
                            ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id
                        WHERE s.kind IN ('llm', 'agent') AND s.call_site_id = :callSite
                        ORDER BY s.started_at, s.created_at, s.trace_id, s.id""")
                .param("pid", projectId)
                .param("scoredTraceId", scoredTraceId)
                .param("callSite", callSiteId)
                .param("turns", turns)
                .query((rs, n) -> map(rs))
                .list();
        int count = jdbc.sql("SELECT COUNT(*) " + earlier)
                .param("pid", projectId)
                .param("scoredTraceId", scoredTraceId)
                .param("callSite", callSiteId)
                .query(Integer.class)
                .single();
        return new PriorTurns(spans, count);
    }

    /**
     * The id halves are bound as sets and the pairs filtered here, as {@link #groundingEvidence} does, so a span id
     * recurring in another trace is not read for it.
     */
    public List<SubstrateObservation> observationsByIds(
            String projectId, java.util.Collection<GroundingEvidenceReads.SpanRef> spans) {
        if (spans.isEmpty()) return List.of();
        java.util.Set<GroundingEvidenceReads.SpanRef> wanted = new java.util.HashSet<>(spans);
        return jdbc
                .sql(SELECT_SPAN + " WHERE s.project_id = :pid AND s.trace_id IN (:tids) AND s.id IN (:sids)")
                .param("pid", projectId)
                .param(
                        "tids",
                        wanted.stream()
                                .map(GroundingEvidenceReads.SpanRef::traceId)
                                .distinct()
                                .toList())
                .param(
                        "sids",
                        wanted.stream()
                                .map(GroundingEvidenceReads.SpanRef::spanId)
                                .distinct()
                                .toList())
                .query((rs, n) -> map(rs))
                .list()
                .stream()
                .filter(o -> wanted.contains(new GroundingEvidenceReads.SpanRef(o.traceId(), o.observationId())))
                .toList();
    }

    /**
     * Adds the keyset predicate only when there is a cursor: Postgres cannot infer the type of untyped null binds in a
     * row-value comparison.
     */
    private JdbcClient.StatementSpec spec(
            String select, String projectId, @Nullable String afterTs, @Nullable String afterHandle, int limit) {
        Cursor cursor = afterTs == null ? null : parseHandle(afterHandle);
        String cursorClause = cursor == null
                ? ""
                : " AND (s.created_at, s.trace_id, s.id) > (:afterTs::timestamptz, :afterTraceId, :afterSpanId)";
        var spec = jdbc.sql(select + " WHERE s.project_id = :pid" + cursorClause
                        + " ORDER BY s.created_at ASC, s.trace_id ASC, s.id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit);
        if (cursor != null) {
            spec = spec.param("afterTs", afterTs)
                    .param("afterTraceId", cursor.traceId())
                    .param("afterSpanId", cursor.spanId());
        }
        return spec;
    }

    record Cursor(String traceId, String spanId) {}

    /**
     * Null when not a {@code "<trace_id>:<span_id>"} handle, including a bare id from an older release: a sweep that
     * threw on a stale cursor would never get past it. Splits on the LAST colon, since a trace id may contain one.
     */
    static @Nullable Cursor parseHandle(@Nullable String handle) {
        if (handle == null) return null;
        int cut = handle.lastIndexOf(':');
        if (cut <= 0 || cut == handle.length() - 1) return null;
        return new Cursor(handle.substring(0, cut), handle.substring(cut + 1));
    }

    public static String handle(String traceId, String spanId) {
        return traceId + ":" + spanId;
    }

    /**
     * The payload join is LEFT so a span whose payload aged out still appears with null text, which detectors read as
     * "nothing to score".
     */
    private static final String SPAN_COLUMNS = """
            SELECT s.id                 AS span_id,
                   s.project_id         AS project_id,
                   s.trace_id           AS trace_id,
                   s.session_id         AS session_id,
                   s.project_version_id AS project_version_id,
                   s.call_site_id       AS call_site_id,
                   s.kind               AS kind,
                   s.name               AS name,
                   pl.input             AS input,
                   pl.output            AS output,
                   pl.redactions::text  AS redactions,
                   (SELECT MIN(tc.error_type) FROM tool_call tc
                      WHERE tc.project_id = s.project_id AND tc.trace_id = s.trace_id
                        AND tc.span_id = s.id AND tc.error_type IS NOT NULL) AS tool_error,
                   s.created_at         AS created_at""";

    private static final String TURN_CANDIDATE_COLUMN = """
            ,
                   (s.call_site_id IS NOT NULL
                    AND s.kind IN ('llm', 'agent')
                    AND EXISTS (SELECT 1 FROM trace tr
                                 WHERE tr.project_id = s.project_id AND tr.id = s.trace_id
                                   AND tr.parent_trace_id IS NULL)
                    AND NOT EXISTS (SELECT 1 FROM span e
                                     WHERE e.project_id = s.project_id AND e.trace_id = s.trace_id
                                       AND e.call_site_id = s.call_site_id AND e.kind IN ('llm', 'agent')
                                       AND (e.started_at, e.id) < (s.started_at, s.id))) AS opens_call_site_turn""";

    private static final String SPAN_FROM = """

            FROM span s
              LEFT JOIN span_payload pl
                ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id""";

    private static final String SELECT_SPAN = SPAN_COLUMNS + SPAN_FROM;

    private static final String SELECT_TURN_CANDIDATE = SPAN_COLUMNS + TURN_CANDIDATE_COLUMN + SPAN_FROM;

    private static SubstrateObservation map(ResultSet rs) throws SQLException {
        return new SubstrateObservation(
                rs.getString("span_id"),
                rs.getString("project_id"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("project_version_id"),
                rs.getString("call_site_id"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("input"),
                rs.getString("output"),
                rs.getString("tool_error"),
                ai.tessary.storage.Timestamps.iso(rs, "created_at"),
                rs.getString("redactions"));
    }
}

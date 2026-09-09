// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The substrate read surface for the TRACE-grain behaviour-drift sweep. Deliberately separate from
 * {@link SubstrateReadRepository}'s projection rather than widening it: this sweep reads no text at
 * all, only the action skeleton, and paying for payload joins on every span of every trace would be
 * the single most expensive thing this classifier does.
 *
 * <h2>The trace head is one row now</h2>
 *
 * <p>{@link #SELECT_TRACE_HEAD} used to be a four-way join, {@code trace} to its turn context to the
 * ltree-root session, plus a LATERAL into {@code observation} to find the entry point's call site. All
 * four are columns on {@code trace} in v2: {@code session_id} and {@code project_version_id} are
 * denormalized at ingest, and {@code call_site_id} is copied down from the ROOT SPAN by the rollup
 * worker's recompute (implementation plan §2.2). The entry-point rule that LATERAL encoded, root span
 * first, then the {@code seq → started_at → created_at} fallback chain, now lives in the recompute,
 * resolved once per trace at write time instead of once per trace per sweep.
 *
 * <h2>Settling is a fact, not a wait</h2>
 *
 * <p>Every read here used to exclude traffic younger than a configured window, because a {@code trace}
 * row existed from its FIRST span and sweeping it early truncated the trajectory. {@code
 * trace.is_settled} answers the actual question, "has anything arrived since the last rollup", so the
 * window is gone. That is strictly better than a longer window would have been: a trace that is still
 * receiving spans after ten minutes was swept anyway under the old rule, and a trace that finished in
 * 400ms had to wait out the clock for nothing.
 */
@Repository
public class BehaviorSubstrateRepository {

    private final JdbcClient jdbc;

    public BehaviorSubstrateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A trace's identity and lineage, everything a trace-grain verdict needs to stamp its subject.
     *
     * @param sessionId the producer session this trace belongs to, or null for anonymous traffic. v2
     *     synthesizes nothing to fill that hole (spec §2.1), so null is a real answer here and every
     *     consumer has to tolerate it, which is why {@link #subjectSessionId()} exists.
     * @param callSiteId the scope this trace's baseline belongs to, the ENTRY POINT's call site, copied
     *     onto the trace from its root span by the rollup recompute, or {@link #UNATTRIBUTED} when the
     *     producer tagged none. Behaviour is per call site, because a project's call sites are separate
     *     products that ship separately; pooling them makes one call site's normal action the thing that
     *     hides another's novelty.
     * @param eventAt the trace's own start as an ISO-8601 instant, both the event clock every
     *     span-of-time rule reads AND the sweep cursor, which in v1 were two different columns that could
     *     disagree. Read through {@link ai.tessary.storage.Timestamps#iso}, NOT {@code getString},
     *     which on a {@code timestamptz} renders the driver's own form ({@code 2026-07-28
     *     18:46:37.416851+05:30}: space separator, JVM-zone offset) that {@code Instant.parse} rejects
     *     outright. Getting that wrong is silent: every span rule fails its parse and falls through to a
     *     wall-clock default.
     */
    public record TraceHead(
            String traceId,
            @Nullable String sessionId,
            @Nullable String projectVersionId,
            String callSiteId,
            String eventAt) {

        /**
         * What a verdict or annotation over this trace puts in its NOT-NULL {@code session_id}: the
         * producer session id when there is one, else the trace's own id. The same rule the backfill's
         * phase-C sweep applies to historical rows, named once so a live writer cannot drift from it.
         */
        public String subjectSessionId() {
            return sessionId != null ? sessionId : traceId;
        }
    }

    /**
     * One action of a trace, reduced to what the alphabet reads.
     *
     * @param observationId the span's own id. A tool a call dispatched may be recorded as that call's
     *     CHILD rather than its sibling, so bounding a fan-out needs both ends of the edge.
     * @param parentObservationId the span's parent, or null when ingest could not resolve one. Read
     *     only to bound a fan-out block: a member must be demonstrably a sibling of the dispatching
     *     LLM call or its child. Null is never treated as matching. Ingest now repairs a parent that
     *     shipped in a later batch than its children, so this is no longer the routine outcome it was,
     *     but null still means "unknown" rather than "no parent", a span ingested before that repair
     *     existed, or one whose parent never arrived, keeps it, and reading two unknowns as shared
     *     parentage would fold unrelated spans into one batch.
     */
    public record TraceAction(
            String traceId,
            @Nullable String kind,
            @Nullable String name,
            boolean isError,
            @Nullable String observationId,
            @Nullable String parentObservationId) {}

    /**
     * The scope for traces whose producer tagged no call site, kept separate, never merged in.
     *
     * <p>Spelled as a literal in {@link #SELECT_TRACE_HEAD} and in {@code behavior_profile}'s column
     * default rather than spliced in: concatenating into a text block is legal but reformats badly,
     * and this value already has to agree with the migration's DEFAULT regardless.
     */
    public static final String UNATTRIBUTED = "__unattributed__";

    private static final String SELECT_TRACE_HEAD = """
            SELECT tr.id                                          AS trace_id,
                   tr.session_id                                  AS session_id,
                   tr.project_version_id                          AS project_version_id,
                   -- The entry point decides the scope: a trace legitimately spans several call sites,
                   -- so a baseline scoped to a child would model "traces that happened to contain this
                   -- tool" rather than "traffic that entered here".
                   COALESCE(tr.call_site_id, '__unattributed__')   AS call_site_id,
                   -- The trace's own start. Trace carries no created_at, since when the row was written
                   -- is not a fact about the turn; every span-of-time rule reads started_at instead.
                   tr.started_at                                  AS event_at
            FROM trace tr
            WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE""";

    /**
     * Traces strictly after the keyset cursor {@code (afterTs, afterId)} that are settled, oldest first,
     * using the same monotonic, gap-free cursor shape every other sweep uses.
     *
     * <p>{@code is_settled} answers "has anything arrived since the last rollup", so a trace is not swept
     * mid-trajectory. A trace that settles late, after the cursor has passed its {@code started_at}, is
     * not re-visited; the rollup worker's ~10s re-fire cadence keeps that window small, and a cursor on
     * settle time instead would re-scan the whole corpus on every straggler.
     */
    public List<TraceHead> tracesAfter(
            String projectId, @Nullable String afterTs, @Nullable String afterId, int limit) {
        String cursorClause =
                (afterTs == null) ? "" : " AND (tr.started_at, tr.id) > (:afterTs::timestamptz, :afterId)";
        var spec = jdbc.sql(SELECT_TRACE_HEAD + " AND tr.is_settled" + cursorClause + """

                ORDER BY tr.started_at ASC, tr.id ASC
                LIMIT :limit
                """)
                .param("pid", projectId)
                .param("limit", limit);
        if (afterTs != null) {
            spec = spec.param("afterTs", afterTs).param("afterId", afterId);
        }
        return spec.query((rs, n) -> mapHead(rs)).list();
    }

    /**
     * Settled traces of ONE call site strictly after {@code (afterTs, afterId)} and at or before
     * {@code (upToTs, upToId)}, oldest first: the catch-up lane behind the project-wide cursor.
     * The project cursor is one keyset for every scope, so a scope whose traces land behind it (a
     * backfill, a replay, an agent onboarded with history behind a sibling's live traffic) was never
     * swept; this reads that scope from its own counted-through watermark up to the cursor, on the
     * same {@code started_at} clock and the same {@code is_settled} filter as {@link #tracesAfter}.
     */
    public List<TraceHead> tracesForScopeBetween(
            String projectId,
            String callSiteId,
            @Nullable String afterTs,
            @Nullable String afterId,
            String upToTs,
            String upToId,
            int limit) {
        String afterClause = (afterTs == null) ? "" : " AND (tr.started_at, tr.id) > (:afterTs::timestamptz, :afterId)";
        var spec = jdbc.sql(SELECT_TRACE_HEAD + " AND tr.is_settled"
                        + " AND COALESCE(tr.call_site_id, '__unattributed__') = :callSiteId"
                        + " AND (tr.started_at, tr.id) <= (:upToTs::timestamptz, :upToId)" + afterClause + """

                ORDER BY tr.started_at ASC, tr.id ASC
                LIMIT :limit
                """)
                .param("pid", projectId)
                .param("callSiteId", callSiteId)
                .param("upToTs", upToTs)
                .param("upToId", upToId)
                .param("limit", limit);
        if (afterTs != null) {
            spec = spec.param("afterTs", afterTs).param("afterId", afterId);
        }
        return spec.query((rs, n) -> mapHead(rs)).list();
    }

    /**
     * Call sites with a settled trace at or before the cursor and not older than {@code notBefore}, other
     * than the ones named: the scopes the catch-up lane has never opened. Bounded below by time so
     * the scan rides the {@code (project_id, started_at)} index over a window rather than the whole
     * history; a scope whose only traces are older than that bound is outside every fit window anyway.
     */
    public List<String> scopesBehindCursor(
            String projectId, String upToTs, String upToId, String notBefore, List<String> excluding, int limit) {
        String exclusion =
                excluding.isEmpty() ? "" : " AND COALESCE(tr.call_site_id, '__unattributed__') NOT IN (:excluding)";
        var spec = jdbc.sql("SELECT DISTINCT COALESCE(tr.call_site_id, '__unattributed__') AS call_site_id"
                        + " FROM trace tr WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE AND tr.is_settled"
                        + " AND (tr.started_at, tr.id) <= (:upToTs::timestamptz, :upToId)"
                        + " AND tr.started_at >= :notBefore::timestamptz" + exclusion + " LIMIT :limit")
                .param("pid", projectId)
                .param("upToTs", upToTs)
                .param("upToId", upToId)
                .param("notBefore", notBefore)
                .param("limit", limit);
        if (!excluding.isEmpty()) {
            spec = spec.param("excluding", excluding);
        }
        return spec.query(String.class).list();
    }

    /**
     * The most-recent {@code limit} traces of ONE call-site scope, the bounded sample the fit job
     * refits that epoch's alphabet from.
     *
     * <p>Scoped, not project-wide. The alphabet is a property of the epoch, and epochs are per call
     * site: a project-wide sample gives every epoch the same rare-symbol set, so a symbol that is
     * rare across the project but routine at one call site is collapsed to {@code __rare__} there,
     * reintroducing exactly the masking the per-call-site grain exists to remove.
     */
    public List<TraceHead> sampleTraces(String projectId, String callSiteId, int limit) {
        return jdbc.sql(SELECT_TRACE_HEAD + """

                  AND COALESCE(tr.call_site_id, '__unattributed__') = :callSiteId
                ORDER BY tr.started_at DESC, tr.id DESC
                LIMIT :limit
                """)
                .param("pid", projectId)
                .param("callSiteId", callSiteId)
                .param("limit", limit)
                .query((rs, n) -> mapHead(rs))
                .list();
    }

    /**
     * The trailing slice of one call-site scope: how many of its most recent traces were sampled and
     * the EVENT time of the oldest one in that slice.
     *
     * @param traces how many traces the slice actually holds, fewer than {@code limit} when the scope
     *     is smaller than that, which is the caller's signal that the slice cannot resolve a rate.
     * @param boundary the oldest sampled trace's event time, as an ISO-8601 instant. The half-open
     *     window {@code [boundary, now]} is what a discovery count is measured against.
     */
    public record TailWindow(long traces, String boundary) {}

    /**
     * The most recent {@code limit} traces of one call-site scope, by EVENT time, the denominator for a
     * discovery rate on a corpus that has stopped growing.
     *
     * <p>In v1 this had to say "ordered by event_at, not created_at" because a backfill landed its whole
     * corpus in one ingest burst and "most recent by ingest time" was an artefact of the writer's
     * chunking. {@code trace} has no ingest clock at all, so the distinction has been designed away
     * rather than defended: {@code started_at} is the only ordering available, and it is the one the
     * n-gram {@code first_seen_at} stamps are on, so the two agree by construction.
     *
     * <p>Scoped per call site to match the epoch grain, using the same predicate as {@link #sampleTraces}
     *, a project-wide tail would measure a different population than the one counted into the profile.
     */
    public Optional<TailWindow> tailWindow(String projectId, String callSiteId, int limit) {
        return jdbc.sql("SELECT count(*) AS traces, min(t.event_at) AS boundary FROM (" + SELECT_TRACE_HEAD + """

                              AND COALESCE(tr.call_site_id, '__unattributed__') = :callSiteId
                            ORDER BY tr.started_at DESC, tr.id DESC
                            LIMIT :limit
                        ) t
                        """)
                .param("pid", projectId)
                .param("callSiteId", callSiteId)
                .param("limit", limit)
                // An aggregate always returns exactly one row, so `single()` is the honest read and the
                // emptiness lives in the VALUES: count(*) is 0 and min() is null on an empty slice. The
                // mapper cannot answer with null (a row exists), so the Optional is derived after.
                .query((rs, n) -> new TailAgg(rs.getLong("traces"), ai.tessary.storage.Timestamps.iso(rs, "boundary")))
                .single()
                .toWindow();
    }

    /** The aggregate as the row really comes back: a count that is always present, a boundary that is not. */
    private record TailAgg(long traces, @Nullable String boundary) {
        Optional<TailWindow> toWindow() {
            // A boundary-less slice is the ABSENCE of a measurement, not a rate of zero, the caller
            // divides by `traces`, and a zero denominator would publish infinity as a discovery rate.
            return (traces <= 0 || boundary == null) ? Optional.empty() : Optional.of(new TailWindow(traces, boundary));
        }
    }

    /**
     * Every action of the given traces, in span order, one read per batch rather than one per trace.
     * Ordering is {@code (started_at, id)}: {@code started_at} is NOT NULL and is the column that
     * actually orders an agent's actions, with the span id breaking ties deterministically.
     */
    public List<TraceAction> actionsForTraces(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        return jdbc.sql("""
            SELECT s.trace_id AS trace_id,
                   s.kind     AS kind,
                   -- The alphabet keys on the tool's name, never the span's: a producer following the
                   -- gen_ai convention names the span `execute_tool verify_member`, so reading `s.name`
                   -- would put the operation verb inside every tool symbol. Three sources, in precedence:
                   --   1. tool_call.name (the normalized column)
                   --   2. the raw attribute
                   --   3. the span name (a producer that sets neither)
                   COALESCE(tcn.name, pl.attributes->>'gen_ai.tool.name', s.name) AS name,
                   s.id       AS span_id,
                   s.parent_span_id AS parent_span_id,
                   (s.error_type IS NOT NULL
                    OR EXISTS (SELECT 1 FROM tool_call tc
                                WHERE tc.project_id = s.project_id
                                  AND tc.trace_id = s.trace_id AND tc.span_id = s.id
                                  AND tc.is_deleted IS NOT TRUE
                                  AND (COALESCE(tc.is_error, false) OR tc.error_type IS NOT NULL))) AS is_error
            FROM span s
            LEFT JOIN span_payload pl
              ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id
            -- Exactly one row per span. `tool_call` is unique only on (span_id, source_external_id),
            -- and that column is nullable, so a re-ingest can leave two rows; a plain join would
            -- duplicate the action.
            LEFT JOIN LATERAL (
                SELECT tc2.name
                  FROM tool_call tc2
                 WHERE tc2.project_id = s.project_id AND tc2.trace_id = s.trace_id
                   AND tc2.span_id = s.id AND tc2.is_deleted IS NOT TRUE AND tc2.name IS NOT NULL
                 ORDER BY tc2.started_at ASC NULLS LAST, tc2.created_at ASC, tc2.id ASC
                 LIMIT 1
            ) tcn ON TRUE
            WHERE s.project_id = :pid AND s.trace_id IN (:ids) AND s.is_deleted IS NOT TRUE
            ORDER BY s.trace_id ASC, s.started_at ASC, s.id ASC
            """)
                .param("pid", projectId)
                .param("ids", traceIds)
                .query((rs, n) -> new TraceAction(
                        rs.getString("trace_id"),
                        rs.getString("kind"),
                        rs.getString("name"),
                        rs.getBoolean("is_error"),
                        rs.getString("span_id"),
                        rs.getString("parent_span_id")))
                .list();
    }

    /** One agent utterance: the output of an llm span, with the span it came from. */
    public record TraceText(String traceId, String observationId, String output) {}

    /**
     * The call site a finding's exemplar trace should be graded against.
     *
     * <p>One column read now, not a LATERAL with a four-deep fallback chain: the rollup recompute already
     * resolved the entry point's call site onto the trace, which is precisely the value the profile is
     * SCOPED to. Deriving it a second time here was the one place the two could silently disagree about
     * which bucket a trace belonged to; Layer 2 would then have been escalated against a different scope
     * than the one that fired. Empty when the trace never carried one: an untagged trace has nothing to
     * escalate to.
     */
    public Optional<String> exemplarCallSite(String projectId, String traceId) {
        return jdbc.sql("SELECT call_site_id FROM trace "
                        + "WHERE project_id = :pid AND id = :tid AND call_site_id IS NOT NULL")
                .param("pid", projectId)
                .param("tid", traceId)
                .query(String.class)
                .optional();
    }

    /**
     * The session a trace belongs to: what a trace-grain annotation puts in its NOT-NULL
     * {@code session_id}. Empty for anonymous traffic, which v2 represents honestly as a null session
     * rather than by synthesizing one; the caller falls back to the trace's own id.
     */
    public Optional<String> traceSessionId(String projectId, String traceId) {
        return jdbc.sql("SELECT session_id FROM trace WHERE project_id = :pid AND id = :tid")
                .param("pid", projectId)
                .param("tid", traceId)
                .query(String.class)
                .optional();
    }

    /**
     * The deploy one trace ran under: what a Layer-2 triage builds its commit range from.
     *
     * <p>Deliberately the exemplar's own version rather than the finding's {@code since_version_id}: the
     * latter is the deploy a reference was pinned at, and asking the repo what changed since then would
     * describe the wrong window when the shifted traffic ran under something later. Empty when the trace
     * never carried one; the triage agent falls back to the default branch.
     */
    public Optional<String> traceProjectVersionId(String projectId, String traceId) {
        return jdbc.sql("SELECT project_version_id FROM trace WHERE project_id = :pid AND id = :tid")
                .param("pid", projectId)
                .param("tid", traceId)
                .query(String.class)
                .optional();
    }

    private static TraceHead mapHead(ResultSet rs) throws SQLException {
        return new TraceHead(
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("project_version_id"),
                rs.getString("call_site_id"),
                eventAt(rs));
    }

    /**
     * {@code event_at} as an ISO-8601 instant. {@code trace.started_at} is NOT NULL in v2, so this cannot
     * be absent; it throws rather than returning null because a null {@code eventAt} would make every
     * downstream span-of-time comparison fall through to a wall-clock default, silently.
     */
    private static String eventAt(ResultSet rs) throws SQLException {
        String iso = ai.tessary.storage.Timestamps.iso(rs, "event_at");
        if (iso == null) throw new SQLException("event_at is null for trace " + rs.getString("trace_id"));
        return iso;
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The trace-head read surface: {@link #tracesAfter} feeds the metric-drift sweep. Deliberately
 * separate from {@link SubstrateReadRepository}'s projection rather than widening it: these reads need
 * no text at all, and paying for payload joins on every span of every trace would be wasted.
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
     *     consumer has to tolerate it.
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
            String eventAt) {}

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
     * be absent.
     */
    private static String eventAt(ResultSet rs) throws SQLException {
        return Objects.requireNonNull(
                ai.tessary.storage.Timestamps.iso(rs, "event_at"), "trace.started_at is NOT NULL");
    }
}

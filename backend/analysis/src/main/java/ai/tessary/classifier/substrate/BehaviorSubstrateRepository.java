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
 * Trace-head reads for the metric-drift sweep, separate from {@link SubstrateReadRepository} because they need no text.
 * {@code session_id}, {@code project_version_id} and {@code call_site_id} are denormalized on {@code trace};
 * {@code trace.is_settled} replaces any age window.
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
     * @param callSiteId the scope this trace's baseline belongs to, copied onto the trace by the rollup
     *     recompute (the root span's call site, else the first one the turn reached), or {@link
     *     #UNATTRIBUTED} when the producer tagged no span. Behaviour is per call site, because a project's call sites are separate
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
     * Kept separate, never merged in. Spelled as a literal here and in {@code behavior_profile}'s column default, which
     * it must match.
     */
    public static final String UNATTRIBUTED = "__unattributed__";

    private static final String SELECT_TRACE_HEAD = """
            SELECT tr.id                                          AS trace_id,
                   tr.session_id                                  AS session_id,
                   tr.project_version_id                          AS project_version_id,
                   -- One call site decides the scope, the root's or else the first one reached: a trace
                   -- legitimately spans several, and counting a turn in each would count it twice.
                   COALESCE(tr.call_site_id, '__unattributed__')   AS call_site_id,
                   -- The trace's own start. Trace carries no created_at, since when the row was written
                   -- is not a fact about the turn; every span-of-time rule reads started_at instead.
                   tr.started_at                                  AS event_at
            FROM trace tr
            WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE""";

    /**
     * Settled traces after the keyset cursor, oldest first. A trace that settles after the cursor passed its
     * {@code started_at} is not revisited; the rollup's ~10s cadence keeps that small, and a settle-time cursor would
     * rescan on every straggler.
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

    /** {@code trace.started_at} is NOT NULL, so this cannot be absent. */
    private static String eventAt(ResultSet rs) throws SQLException {
        return Objects.requireNonNull(
                ai.tessary.storage.Timestamps.iso(rs, "event_at"), "trace.started_at is NOT NULL");
    }
}

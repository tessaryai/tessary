// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Feature-owned cross-read of the trace substrate for the case surface — the before/after exemplars on a
 * case page, and the coverage counts Triage's all-clear state cites. Same pattern
 * {@link ai.tessary.rca.RcaFacetRepository} uses for {@code span}, which the
 * {@code jdbc_client_only_in_repositories} ArchUnit rule allows.
 *
 * <p><b>Hydration only: this repository no longer chooses which traces a case shows.</b> It used to
 * SAMPLE them — a detector's window is a population summarized into a sketch, the ids that made it up
 * were not stored, so the page went back to the substrate and pulled fair members of the same scope over
 * the same hours. That meant a second implementation of the detector's own scope living in the case
 * surface, and an illustration the page then had to disclaim. Both are gone: the classifier records what
 * it saw as {@code finding_evidence} at the moment it saw it, and this turns those ids into rows a
 * reader can tell apart. Nothing here decides anything.
 *
 * <p>Time is read on {@code trace.started_at} — EVENT time, the same clock the metric-drift sweep cuts
 * windows on. The old three-way COALESCE existed because {@code trace} carried an ingest clock that a
 * backfill compressed into minutes; {@code trace} has no ingest clock at all and {@code started_at}
 * is NOT NULL, so the fallback chain has been designed away rather than defended.
 */
@Repository
public class CaseSubstrateRepository {

    private static final String SELECT = """
            SELECT tr.id, tr.name, tr.latency_ms, tr.total_cost, tr.started_at AS at
            FROM trace tr
            WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE
            """;

    private final JdbcClient jdbc;

    public CaseSubstrateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One trace, as a case renders it: enough to tell two of them apart without opening either. */
    public record Exemplar(
            String traceId,
            @Nullable String name,
            @Nullable Long durationMs,
            @Nullable Double costUsd,
            @Nullable String startedAt) {}

    /**
     * Hydrate trace ids the detector already recorded, in the order given — a finding's exemplar first,
     * then its witnesses, which is the order a reader wants and not the order a database returns.
     *
     * <p>An id with no row is dropped rather than rendered as a dead link: retention can age a trace out
     * from under a finding that outlived it, and offering a click that 404s is worse than one fewer
     * example.
     */
    public List<Exemplar> byIds(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        Map<String, Exemplar> found = new HashMap<>();
        for (Exemplar e : jdbc.sql(SELECT + " AND tr.id IN (:ids)")
                .param("pid", projectId)
                .param("ids", traceIds)
                .query(CaseSubstrateRepository::map)
                .list()) {
            found.putIfAbsent(e.traceId(), e);
        }
        List<Exemplar> ordered = new ArrayList<>();
        for (String id : traceIds) {
            Exemplar hit = found.get(id);
            if (hit != null) ordered.add(hit);
        }
        return ordered;
    }

    /**
     * Traces seen since {@code since} — the count that separates the two silences a reader must never
     * confuse. "Nothing needs you" over a thousand traces is coverage; over zero it is a broken exporter,
     * and the all-clear state would otherwise congratulate someone on it (launch requirement E5).
     */
    public long countTracesSince(String projectId, String since) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM trace tr
                WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE
                  AND tr.started_at >= :since::timestamptz""")
                .param("pid", projectId)
                .param("since", since)
                .query(Long.class)
                .single();
    }

    /**
     * Every trace this project has ever kept — the number the empty Triage screen prints beside its
     * headline, so that no headline can deny traffic the reader is looking at.
     *
     * <p>Unbounded on purpose, where {@code spansReceived} and its neighbours cap at a thousand. What
     * makes an all-time count expensive is not the rows but the predicate: {@code ix_trace_project_started}
     * carries no {@code is_deleted}, so Postgres had to visit the heap for every candidate row to decide
     * whether it counted. {@code ix_trace_project_live} (migration 0021) is a partial index whose
     * definition is exactly this WHERE clause, which makes this an index-only scan and the cap
     * unnecessary. Keep the two in step: widen the predicate here and the index stops covering it,
     * silently, with only the latency to show for it.
     *
     * <p>Only called when the answer will be rendered — see {@link CaseService#watching(String, boolean)}.
     */
    public long countTraces(String projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM trace tr
                WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE""").param("pid", projectId).query(Long.class).single();
    }

    /**
     * Distinct call sites the traffic since {@code since} resolved to. Counted from spans rather
     * than from the pipeline, because a launch partner has no pipeline: call sites arrive tagged on spans
     * and exist whether or not anyone ever committed a {@code .tessary} bundle. {@code __unattributed__}
     * is not a call site and is excluded — see {@code MetricDriftSweep} on why that pile is a mixture
     * rather than a population.
     */
    public long countCallSitesSince(String projectId, String since) {
        return jdbc.sql("""
                SELECT COUNT(DISTINCT o.call_site_id) FROM span o
                WHERE o.project_id = :pid AND o.call_site_id IS NOT NULL
                  AND o.call_site_id <> :unattributed
                  AND o.started_at >= :since::timestamptz""")
                .param("pid", projectId)
                .param("unattributed", BehaviorSubstrateRepository.UNATTRIBUTED)
                .param("since", since)
                .query(Long.class)
                .single();
    }

    private static Exemplar map(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        long latency = rs.getLong("latency_ms");
        Long durationMs = rs.wasNull() ? null : latency; // must follow its own read — wasNull is per-column
        BigDecimal cost = rs.getBigDecimal("total_cost");
        OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
        return new Exemplar(
                id,
                name,
                durationMs,
                cost == null ? null : cost.doubleValue(),
                at == null ? null : at.toInstant().toString());
    }
}

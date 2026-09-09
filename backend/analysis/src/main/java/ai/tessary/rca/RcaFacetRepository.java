// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Feature-owned reads of {@code span} for the RCA analysis — the pattern the
 * {@code jdbc_client_only_in_repositories} ArchUnit rule allows.
 *
 * <p><b>Every read is keyed on TRACES.</b> RCA used to analyse a CUSUM mover: a grader whose score fell,
 * over two hourly windows, read out of the {@code timing='online'} escalation lane. Both halves of that
 * are gone — the mover went with the watcher, and the online lane has no writers left. What RCA analyses
 * now is a FINDING, and a finding says exactly which substrate it is about: its {@code finding_evidence}
 * rows. So the caller dereferences the evidence into two trace sets — the {@code baseline}-role
 * references (what things looked like before) and everything else (what the classifier flagged) — and
 * every read here is scoped to one of them.
 *
 * <p><b>This was {@code RcaVerdictRepository}, and half of it went with grading.</b> Three reads —
 * {@code graderVersionCounts}, {@code gradingHealth} and {@code executionErrors} — aggregated
 * {@code FROM verdict}, so a checklist could ask whether a grader had been redefined or had started
 * erroring. Grading and the {@code verdict} table are gone. What survives reads
 * {@code span} only, which is why the class is named for the facets rather than for a store.
 *
 * <p><b>The checklist is thinner for it, and that is a known gap rather than a finished shape.</b> The
 * questions those three reads answered — "did the instrument change?", "did the instrument break?" —
 * are real RCA questions with no substitute here yet. Re-sourcing them onto classifier detections is
 * design work filed as a follow-up, not something to improvise in this file.
 */
@Repository
public class RcaFacetRepository {

    /** One (dimension value, count) bucket — the model-mix read. */
    public record FacetCount(@Nullable String value, long count) {}

    /** One span of a cited trace, projected to the facets the deterministic diff groups by. */
    public record ObservationFacet(
            String traceId,
            @Nullable String kind,
            @Nullable String name,
            @Nullable String model,
            @Nullable String errorType,
            @Nullable String status) {}

    private final JdbcClient jdbc;

    public RcaFacetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** These traces' LLM spans grouped by model — the producer's raw model string, not the resolved
     *  model_id: a name the price book could not resolve is exactly the kind of change worth naming
     *  rather than collapsing into a null bucket. */
    public List<FacetCount> modelCounts(String projectId, List<String> traceIds) {
        return spanFacets(projectId, traceIds, "provided_model_name", "AND provided_model_name IS NOT NULL");
    }

    private List<FacetCount> spanFacets(String projectId, List<String> traceIds, String column, String extra) {
        if (traceIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + column + " AS value, COUNT(*) AS n FROM span "
                        + "WHERE project_id = :pid AND trace_id IN (:tids) " + extra + " "
                        + "GROUP BY " + column + " ORDER BY n DESC")
                .param("pid", projectId)
                .param("tids", traceIds)
                .query((rs, n) -> new FacetCount(rs.getString("value"), rs.getLong("n")))
                .list();
    }

    /** Every span of the given traces, projected to the diffable facets. */
    public List<ObservationFacet> observationFacets(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT trace_id, kind, name, provided_model_name AS model, "
                        + "error_type, status "
                        + "FROM span WHERE project_id = :pid AND trace_id IN (:tids)")
                .param("pid", projectId)
                .param("tids", traceIds)
                .query((rs, n) -> new ObservationFacet(
                        rs.getString("trace_id"),
                        rs.getString("kind"),
                        rs.getString("name"),
                        rs.getString("model"),
                        rs.getString("error_type"),
                        rs.getString("status")))
                .list();
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Onboarding progress reads, all derived from existing state, so revoking a key or deleting traffic walks progress back
 * down. They run on a poll, so each is an {@code EXISTS} or a small aggregate.
 */
@Repository
public class OnboardingRepository {

    private final JdbcClient jdbc;

    public OnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether the project holds a key with a span-ingest scope. Not "a source row exists": a source can exist with no
     * way to authenticate.
     */
    public boolean hasIngestKey(String projectId) {
        return exists("""
                SELECT EXISTS(
                    SELECT 1 FROM api_key
                    WHERE project_id = :pid AND revoked_at IS NULL AND scope IN ('write', 'admin')
                )
                """, projectId);
    }

    /** First and last span by event time; index-served by {@code ix_span_project_started}. */
    public Optional<TrafficWindow> trafficWindow(String projectId) {
        return jdbc.sql("""
                        SELECT to_char(MIN(started_at) AT TIME ZONE 'UTC',
                                       'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS first_at,
                               to_char(MAX(started_at) AT TIME ZONE 'UTC',
                                       'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_at
                        FROM span
                        WHERE project_id = :pid
                        """)
                .param("pid", projectId)
                .query((rs, n) -> {
                    String first = rs.getString("first_at");
                    return first == null ? null : new TrafficWindow(first, rs.getString("last_at"));
                })
                .optional()
                .filter(w -> w != null);
    }

    /**
     * A {@code (bucket, measure)} stays learning until both windows clear {@code min_sample}. @param buckets pairs ever
     * opened; zero means no traffic folded yet @param armed pairs with enough history on both sides to compare @param
     * samplesInFlight samples in windows not yet closed
     */
    public BaselineProgress baselineProgress(String projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*)                                          AS buckets,
                               COUNT(*) FILTER (WHERE state = 'armed')           AS armed,
                               COALESCE(SUM(current_count), 0)                   AS samples_in_flight,
                               COALESCE(MAX(current_count), 0)                   AS best_window_count
                        FROM metric_baseline
                        WHERE project_id = :pid
                        """)
                .param("pid", projectId)
                .query((rs, n) -> new BaselineProgress(
                        rs.getLong("buckets"),
                        rs.getLong("armed"),
                        rs.getLong("samples_in_flight"),
                        rs.getLong("best_window_count")))
                .single();
    }

    public FindingProgress findingProgress(String projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS findings, MIN(onset_at) AS first_at
                        FROM finding
                        WHERE project_id = :pid
                        """)
                .param("pid", projectId)
                .query((rs, n) -> new FindingProgress(rs.getLong("findings"), rs.getString("first_at")))
                .single();
    }

    /**
     * The milestone counts only LLM-triaged cases: a case a human opened is one the product did not produce.
     * {@code finding.triaged_at} is non-null exactly when a triage run completed.
     */
    public CaseProgress caseProgress(String projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS cases,
                               COUNT(*) FILTER (WHERE triaged) AS triaged,
                               MIN(c.opened_at) FILTER (WHERE triaged) AS first_at
                        FROM (
                            SELECT c.opened_at,
                                   EXISTS (SELECT 1 FROM finding f
                                            WHERE f.case_id = c.id AND f.triaged_at IS NOT NULL) AS triaged
                            FROM eval_case c
                            WHERE c.project_id = :pid
                        ) c
                        """)
                .param("pid", projectId)
                .query((rs, n) ->
                        new CaseProgress(rs.getLong("cases"), rs.getLong("triaged"), rs.getString("first_at")))
                .single();
    }

    private boolean exists(String sql, String projectId) {
        return Boolean.TRUE.equals(
                jdbc.sql(sql).param("pid", projectId).query(Boolean.class).single());
    }

    public record TrafficWindow(String firstAt, String lastAt) {}

    public record BaselineProgress(long buckets, long armed, long samplesInFlight, long bestWindowCount) {}

    public record FindingProgress(long findings, @Nullable String firstAt) {}

    public record CaseProgress(
            long cases, long triaged, @Nullable String firstAt) {}
}

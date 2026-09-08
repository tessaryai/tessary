// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.onboarding;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads behind the onboarding progress surface — five cheap, independent questions about how far a
 * project has got between "an exporter has been pointed at us" and "a case opened".
 *
 * <p>Every one of them is derived from state the product already keeps. Nothing here is stored progress:
 * revoking the ingest token, or deleting the traffic, walks the progress honestly back down, which is the
 * same property {@code setupProgress.ts} had and the reason the old wizard could be trusted.
 *
 * <p>These run on a poll while a project is warming up, so each is either an {@code EXISTS} that
 * short-circuits or an aggregate over a small per-project table. The one scan-shaped read
 * ({@code span}) is bounded to two index-served extremes rather than a {@code COUNT(*)}.
 */
@Repository
public class OnboardingRepository {

    private final JdbcClient jdbc;

    public OnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether the project has ever minted a key an exporter could push with.
     *
     * <p>This is what "listening" means: the endpoint and the header exist, so traffic sent right now
     * would land. It is deliberately NOT "a source row exists" — a source is a bookkeeping row the
     * connect flow creates as a side effect, and a project can have one with no way to authenticate.
     * Narrowed to the scopes that can actually POST spans, so a project holding only a read key is
     * still, correctly, not listening.
     */
    public boolean hasIngestKey(String projectId) {
        return exists("""
                SELECT EXISTS(
                    SELECT 1 FROM api_key
                    WHERE project_id = :pid AND revoked_at IS NULL AND scope IN ('write', 'admin')
                )
                """, projectId);
    }

    /**
     * First and last span by EVENT time, or empty when nothing has landed.
     *
     * <p>No COALESCE onto an ingest clock any more: {@code span.started_at} is NOT NULL, so the fallback
     * that existed for producers omitting it has nothing left to guard. Index-served by
     * {@code ix_span_project_started} at both extremes.
     */
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
     * How the metric detectors' baselines are coming along.
     *
     * <p>This is the honest content of the warm-up wait (launch G4). A metric detector is not waiting on
     * a clock, it is waiting on COMPARABLE SAMPLES: each {@code (bucket, measure)} accumulates a window,
     * and stays {@code learning} — waiting, never skipped — until both the window it is filling and the
     * one it would compare against clear {@code min_sample}. So "nothing yet" has a number behind it, and
     * a project can be told how far along it is rather than shown an empty screen for days.
     *
     * @param buckets how many {@code (bucket, measure)} pairs the sweep has ever opened. Zero means no
     *     traffic has been folded yet, which is a different state from "not enough of it".
     * @param armed how many have enough history on both sides to produce a comparison at all
     * @param samplesInFlight total samples sitting in windows that have not closed yet — the visible
     *     evidence that the wait is progress and not a stall
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

    /** How many findings the detectors have written, and when the first one landed. */
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
     * Cases, and the subset that reached the onboarding milestone.
     *
     * <p>Launch decision D9: the "first case" milestone means an <b>LLM-triaged</b> case. A human
     * pressing <i>Real deviation</i> opens a perfectly real case and does not count here — the milestone
     * is about whether the product got there unaided, and a case a person had to open is a case the
     * product did not produce. {@code finding.triaged_at} is non-null exactly when a triage run completed
     * (a run that did not happen leaves it NULL rather than recording an empty ruling), so the join is
     * the definition rather than a proxy for it.
     */
    public CaseProgress caseProgress(String projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*)                                        AS cases,
                               COUNT(*) FILTER (WHERE f.triaged_at IS NOT NULL) AS triaged,
                               MIN(c.opened_at) FILTER (WHERE f.triaged_at IS NOT NULL) AS first_at
                        FROM eval_case c
                        LEFT JOIN finding f ON f.id = c.finding_id
                        WHERE c.project_id = :pid
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

    /** The event-time span of everything ingested for a project. */
    public record TrafficWindow(String firstAt, String lastAt) {}

    /** @see #baselineProgress(String) */
    public record BaselineProgress(long buckets, long armed, long samplesInFlight, long bestWindowCount) {}

    /** Findings written so far, and the first one's event time (null when there are none). */
    public record FindingProgress(long findings, @Nullable String firstAt) {}

    /** Cases opened, how many were triaged, and when the first triaged one opened. */
    public record CaseProgress(
            long cases, long triaged, @Nullable String firstAt) {}
}

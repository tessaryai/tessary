// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

/**
 * One project version, keyed by commit SHA. A version is the unit benchmarks
 * attribute to: runs stamp their {@code commit_sha} so reported metrics never
 * blend across versions. Rows are materialized lazily — created the first time
 * something attaches to a SHA (a pipeline sync, a benchmark run, or an observer
 * finding), not for every commit.
 */
public record ProjectVersionRow(
        String id,
        String projectId,
        String commitSha,
        String parentSha,
        String materializedReason,
        String gradersStatus,
        String datasetsStatus,
        String benchmarkStatus,
        String summary,
        String createdAt,
        String updatedAt) {
    public static final String REASON_PIPELINE_SYNC = "pipeline_sync";
    public static final String REASON_BENCHMARK = "benchmark";
    public static final String REASON_OBSERVER_FINDING = "observer_finding";

    public static final String STATUS_SYNCED = "synced";
    public static final String STATUS_STALE = "stale";
    public static final String STATUS_UNKNOWN = "unknown";
}

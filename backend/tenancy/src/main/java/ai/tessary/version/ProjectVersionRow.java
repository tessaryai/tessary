// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

/** Materialized lazily, the first time a pipeline sync attaches to a SHA. */
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

    public static final String STATUS_SYNCED = "synced";
}

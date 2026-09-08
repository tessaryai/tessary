// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.usage;

/**
 * A claimed per-(project, bucket) rollup work item — a {@code metric_rollup_job} row. The worker
 * schedules one of these per closed bucket that has no rollup yet, then claims a batch with
 * {@code FOR UPDATE SKIP LOCKED} so N backends never both run the same aggregation scan. Mirrors the
 * {@code signal_job} / {@code signal_alert} claim idiom.
 *
 * @param bucketStart ISO-8601 start of the closed window to aggregate
 * @param granularity the bucket grain ({@code hour} or {@code day})
 */
public record MetricRollupJobRow(String id, String orgId, String projectId, String bucketStart, String granularity) {}

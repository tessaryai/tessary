// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

/**
 * One {@code metric_rollup_job} row per closed bucket without a rollup, claimed {@code FOR UPDATE SKIP LOCKED} so
 * backends never repeat an aggregation scan. @param granularity {@code hour} or {@code day}
 */
public record MetricRollupJobRow(String id, String orgId, String projectId, String bucketStart, String granularity) {}

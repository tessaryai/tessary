// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * One claimed {@code project_delete} job: the queue row reduced to what the purge worker needs.
 *
 * @param projectId read out of {@code payload}, never out of the {@code project_id} column — see
 *     {@link ProjectDeleteJobRepository} for why that column is NULL on this kind.
 * @param attempts the count AFTER the claim incremented it, which is what decides dead-lettering.
 */
public record ProjectDeleteJobRow(String id, String projectId, String status, int attempts) {}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * @param projectId read from {@code payload}; the column is NULL on this kind (see {@link ProjectDeleteJobRepository})
 * @param attempts the count after the claim incremented it, which decides dead-lettering
 */
public record ProjectDeleteJobRow(String id, String projectId, int attempts) {}

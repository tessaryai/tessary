// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

/**
 * The longest a project may keep a data class, in days; {@code 0} means no ceiling. This build has
 * none, and {@link RetentionSeamConfig} registers that answer only when no other build supplies one.
 * The resolver clamps every effective retention to it, so the sweep and the customer-facing answer
 * both honour it without either knowing where the number came from.
 */
public interface RetentionCeiling {

    int maxTtlDays(String projectId, RetentionResolver.DataClass dataClass);

    static RetentionCeiling none() {
        return (projectId, dataClass) -> 0;
    }
}

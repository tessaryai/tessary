// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

/**
 * A retention another build fixes for a project, in days; {@code 0} means none, so the install default and
 * the project's own override apply. This build fixes nothing, and {@link RetentionSeamConfig} registers that
 * answer only when no other build supplies one. A fixed value replaces both the default and any override,
 * so the sweep and the settings page agree without either knowing where the number came from.
 */
public interface FixedRetention {

    int fixedTtlDays(String projectId, RetentionResolver.DataClass dataClass);

    static FixedRetention none() {
        return (projectId, dataClass) -> 0;
    }
}

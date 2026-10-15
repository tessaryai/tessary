// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

/**
 * A retention another build fixes for a project, in days; {@code 0} means none. A fixed value replaces both the default
 * and any override, so sweep and settings page agree. {@link RetentionSeamConfig} registers this build's "none" only as
 * a fallback.
 */
public interface FixedRetention {

    int fixedTtlDays(String projectId, RetentionResolver.DataClass dataClass);

    static FixedRetention none() {
        return (projectId, dataClass) -> 0;
    }
}

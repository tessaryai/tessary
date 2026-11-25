// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import org.jspecify.annotations.Nullable;

/**
 * A claimed end-user job. {@code day} is the closed UTC day of a daily job and the day of the hour of a hit sweep;
 * {@code lastStage} is the last stage done, null before the first.
 */
public record EndUserJob(
        String id,
        String projectId,
        int day,
        int attempts,
        @Nullable String lastStage) {}

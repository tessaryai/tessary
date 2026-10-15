// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 {@code session} row, keyed by {@code (projectId, id)} where id is the producer's session string. Sessions never
 * nest; sub-grouping is {@code trace.threadId}. No rollup columns: a session can resume days later and never settles,
 * so totals are summed from trace rollups at read time. {@code lastActivityAt} is a {@code max}, idempotent under
 * replay.
 */
public record SessionRow(
        String projectId,
        String id,
        @Nullable String userId,
        String startedAt,
        String lastActivityAt,
        String eventTs,
        boolean isDeleted) {}

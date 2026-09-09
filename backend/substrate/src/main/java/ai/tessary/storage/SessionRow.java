// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * A v2 {@code session} row — one continuous interaction with one user, identity only.
 *
 * <p>The id is the producer's session string, verbatim, and the key is {@code (projectId, id)}: there is
 * no platform-minted surrogate anywhere in the v2 substrate. Sessions never nest — sub-grouping inside a
 * session (a provider's conversation or thread id) is {@code trace.threadId}, a column, not a second tree
 * level.
 *
 * <p><b>No rollup columns, deliberately.</b> A trace goes quiet in seconds; a session can be resumed days
 * later, so no gap of inactivity reliably means "finished" and a session rollup could never legitimately
 * settle. Session totals are summed at read time from the session's already-materialized trace rollups.
 * {@code lastActivityAt} is the one incrementally maintained value, and it is a {@code max} — idempotent
 * under replay, which is what makes updating it in place safe at all.
 *
 * <p>Timestamps ride as ISO-8601 {@code String}s, bound with explicit {@code ::timestamptz} casts and read
 * back through {@link Timestamps}, matching the rest of the substrate.
 */
public record SessionRow(
        String projectId,
        String id,
        @Nullable String userId,
        String startedAt,
        String lastActivityAt,
        String eventTs,
        boolean isDeleted) {

    /** The get-or-create shape: identity plus the two timestamps, both seeded from the first arrival. */
    public static SessionRow of(
            String projectId, String id, @Nullable String userId, String startedAt, String eventTs) {
        return new SessionRow(projectId, id, userId, startedAt, startedAt, eventTs, false);
    }
}

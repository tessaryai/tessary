// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * Read helper for the substrate's {@code timestamptz} columns.
 * The {@code *Row} records carry timestamps as ISO-8601 {@code String}s (bound on write with explicit
 * {@code ::timestamptz} casts); this reads a {@code timestamptz} column back and reformats it to a
 * canonical ISO-8601 instant (e.g. {@code 2026-07-02T12:00:00Z}) so the wire shape is stable and
 * independent of Postgres's textual timestamptz rendering.
 */
public final class Timestamps {

    private Timestamps() {}

    /** A {@code timestamptz} column as an ISO-8601 instant string, or null when the column is null. */
    public static @Nullable String iso(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant().toString();
    }

    /**
     * A {@code timestamptz} column as an {@link Instant}, or null when the column is null — for the callers
     * that compare timestamps rather than serialise them. Goes via {@link OffsetDateTime} because the
     * Postgres driver refuses {@code getObject(col, Instant.class)} outright.
     */
    public static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }
}

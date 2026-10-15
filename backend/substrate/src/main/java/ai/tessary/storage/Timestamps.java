// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * Reads a {@code timestamptz} back as a canonical ISO-8601 instant, so the wire shape does not depend on Postgres's
 * text rendering.
 */
public final class Timestamps {

    private Timestamps() {}

    /** Null when the column is null. */
    public static @Nullable String iso(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant().toString();
    }
}

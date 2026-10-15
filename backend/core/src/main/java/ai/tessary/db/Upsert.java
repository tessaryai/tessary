// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@code INSERT ... ON CONFLICT ... DO UPDATE} and its binds from one ordered column map, so the column list,
 * placeholders, SET clause and binds cannot drift apart. Holds no {@code JdbcClient}, so it stays outside the
 * repository JDBC boundary.
 */
public final class Upsert {

    private final String sql;
    private final Map<String, Object> params;

    private Upsert(String sql, Map<String, Object> params) {
        this.sql = sql;
        this.params = params;
    }

    public String sql() {
        return sql;
    }

    public Map<String, Object> params() {
        return params;
    }

    /**
     * @param values iteration order fixes the INSERT column order @param conflictKeys a subset of {@code values}' keys
     * that leaves at least one column to update
     */
    public static Upsert into(String table, Map<String, Object> values, List<String> conflictKeys) {
        Set<String> keySet = Set.copyOf(conflictKeys);
        List<String> columns = List.copyOf(values.keySet());
        String columnList = String.join(", ", columns);
        StringBuilder placeholders = new StringBuilder();
        StringBuilder updates = new StringBuilder();
        for (String col : columns) {
            if (placeholders.length() > 0) placeholders.append(", ");
            placeholders.append(':').append(col);
            if (keySet.contains(col)) continue;
            if (updates.length() > 0) updates.append(",\n  ");
            updates.append(col).append(" = excluded.").append(col);
        }

        String sql = "INSERT INTO " + table + " (" + columnList + ")\n"
                + "VALUES (" + placeholders + ")\n"
                + "ON CONFLICT (" + String.join(", ", conflictKeys) + ") DO UPDATE SET\n  "
                + updates;

        return new Upsert(sql, new LinkedHashMap<>(values));
    }
}

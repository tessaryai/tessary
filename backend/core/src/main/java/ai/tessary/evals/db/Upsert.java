// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@code INSERT ... ON CONFLICT ... DO UPDATE} statement and its named-parameter binds
 * from a single ordered {@code column -> value} map, so the INSERT column list, the VALUES
 * placeholders, the {@code DO UPDATE SET col = excluded.col} clause, and the parameter binds
 * cannot drift apart. Each column is bound under a placeholder named after the column
 * itself ({@code :judge_prompt}), so the column name lives in exactly one place.
 *
 * <p>Pure SQL + parameter assembly — it holds no {@code JdbcClient}, so it stays outside the
 * {@code @Repository} JDBC boundary; the calling repository feeds {@link #sql()} to
 * {@code jdbc.sql(...)} and {@link #params()} to {@code .params(...)}.
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
     * @param table the target table
     * @param values ordered map of column name to bound value; iteration order fixes the INSERT
     *     column order. Must be non-empty.
     * @param conflictKeys the columns forming the conflict target (the {@code ON CONFLICT (...)}
     *     tuple). Must be a subset of {@code values}' keys and non-empty.
     * @return the assembled statement; every non-conflict-key column is updated on conflict from
     *     {@code excluded}.
     */
    public static Upsert into(String table, Map<String, Object> values, List<String> conflictKeys) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("upsert into " + table + " has no columns");
        }
        if (conflictKeys.isEmpty()) {
            throw new IllegalArgumentException("upsert into " + table + " has no conflict keys");
        }
        Set<String> keySet = Set.copyOf(conflictKeys);
        if (!values.keySet().containsAll(keySet)) {
            throw new IllegalArgumentException("upsert into " + table + " conflict keys not in column set");
        }
        if (keySet.containsAll(values.keySet())) {
            // Every column is a conflict key, so DO UPDATE SET would be empty -> malformed SQL.
            // This helper deliberately does not model DO NOTHING; fail loudly rather than emit it.
            throw new IllegalArgumentException(
                    "upsert into " + table + " has no non-conflict-key columns to update on conflict");
        }

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

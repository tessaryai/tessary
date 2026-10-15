// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts seed rows written as pipe tables, one table per header line:
 *
 * <pre>
 * trace | project_id | id | started_at
 *       | $1         | t1 | $2
 * </pre>
 *
 * A header starts at the left margin and names the table and its columns; each indented line under it is one row.
 * {@code $n} is the n-th argument, {@code null} is SQL NULL, and every other cell is a string literal, which Postgres
 * casts to the column's type.
 */
public final class SeedRows {

    private static final Pattern ARG = Pattern.compile("\\$(\\d+)");

    private SeedRows() {}

    public static void insert(JdbcClient jdbc, String tables, @Nullable Object... args) {
        String table = null;
        List<String> columns = List.of();
        List<String> rows = new ArrayList<>();
        for (String line :
                tables.stripIndent().lines().filter(l -> !l.isBlank()).toList()) {
            List<String> cells =
                    List.of(line.split("\\|", -1)).stream().map(String::strip).toList();
            if (!cells.get(0).isEmpty()) {
                flush(jdbc, table, columns, rows);
                table = cells.get(0);
                columns = cells.subList(1, cells.size());
                rows = new ArrayList<>();
            } else {
                rows.add(cells.subList(1, cells.size()).stream()
                        .map(c -> literal(c, args))
                        .reduce((a, b) -> a + ", " + b)
                        .map(r -> "(" + r + ")")
                        .orElseThrow());
            }
        }
        flush(jdbc, table, columns, rows);
    }

    private static void flush(JdbcClient jdbc, @Nullable String table, List<String> columns, List<String> rows) {
        if (table == null) return;
        if (rows.isEmpty()) throw new IllegalArgumentException("no rows under " + table);
        jdbc.sql("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES " + String.join(", ", rows))
                .update();
    }

    private static String literal(String cell, @Nullable Object[] args) {
        Matcher whole = ARG.matcher(cell);
        if (whole.matches() && args[Integer.parseInt(whole.group(1)) - 1] == null) return "NULL";
        if (whole.matches() && args[Integer.parseInt(whole.group(1)) - 1] instanceof byte[] bytes) {
            return "'\\x" + HexFormat.of().formatHex(bytes) + "'";
        }
        if (cell.equals("null")) return "NULL";
        String value = ARG.matcher(cell)
                .replaceAll(m -> Matcher.quoteReplacement(String.valueOf(args[Integer.parseInt(m.group(1)) - 1])));
        return "'" + value.replace("'", "''") + "'";
    }
}

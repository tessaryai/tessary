// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Accumulates optional {@code AND <predicate>} clauses together with their named-parameter binds,
 * so a query's dynamic WHERE fragment and the parameters it references are built from a single call
 * and cannot drift apart (a predicate appended without its bind, or a bind set without its
 * predicate, is the silent-wrong-results bug class this removes).
 *
 * <p>Holds no {@code JdbcClient}: the caller appends {@link #sql()} to its query text and applies
 * {@link #params()} via {@code spec.params(...)}.
 */
public final class SqlFilter {

    private final List<String> predicates = new ArrayList<>();
    private final Map<String, Object> params = new LinkedHashMap<>();

    /**
     * Append {@code AND <predicate>} bound to {@code :param = value}, but only when {@code value}
     * is non-null. {@code predicate} must reference the {@code :param} placeholder.
     */
    public SqlFilter and(String predicate, String param, @Nullable Object value) {
        if (value == null) return this;
        predicates.add(predicate);
        params.put(param, value);
        return this;
    }

    /** Append {@code AND <predicate>} with no bind (a static, value-free condition). */
    public SqlFilter and(String predicate) {
        predicates.add(predicate);
        return this;
    }

    /** The accumulated clause, each predicate prefixed with {@code " AND "} (empty when none). */
    public String sql() {
        StringBuilder sb = new StringBuilder();
        for (String predicate : predicates) {
            sb.append(" AND ").append(predicate);
        }
        return sb.toString();
    }

    public Map<String, Object> params() {
        return Map.copyOf(params);
    }
}

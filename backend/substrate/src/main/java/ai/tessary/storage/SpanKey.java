// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A span's identity inside its project. {@code project_id} is deliberately absent: it comes from the token, never a
 * caller's list. For multi-key hydration of {@code span} and {@code span_payload}; the helpers state the index-eligible
 * predicate once.
 */
public record SpanKey(String traceId, String spanId) {

    /** The id a search row carries and a caller passes back. */
    public String handle() {
        return traceId + ':' + spanId;
    }

    /**
     * Index-eligible on the primary-key prefix. Column names are trusted constants; values are bound by {@link #bind}.
     * {@code keyCount} must be positive, since {@code IN ()} is a syntax error.
     */
    static String tupleIn(String traceColumn, String spanColumn, int keyCount) {
        List<String> tuples = new ArrayList<>(keyCount);
        for (int i = 0; i < keyCount; i++) {
            tuples.add("(:k" + i + "t, :k" + i + "s)");
        }
        return "(" + traceColumn + ", " + spanColumn + ") IN (" + String.join(", ", tuples) + ")";
    }

    static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, List<SpanKey> keys) {
        JdbcClient.StatementSpec bound = spec;
        for (int i = 0; i < keys.size(); i++) {
            bound = bound.param("k" + i + "t", keys.get(i).traceId());
            bound = bound.param("k" + i + "s", keys.get(i).spanId());
        }
        return bound;
    }
}

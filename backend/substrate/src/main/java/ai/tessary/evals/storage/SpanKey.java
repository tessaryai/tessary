// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A span's identity inside its project: {@code (traceId, spanId)}. The third component,
 * {@code project_id}, is never part of this record — it comes from the token, not from a caller's list, and
 * putting it here would invite a read whose scope arrived in its argument.
 *
 * <p>This exists for the <b>multi-key</b> reads: a page of spans chosen elsewhere (a search page, a kNN
 * ranking) that then has to be hydrated from {@code span} and {@code span_payload} without a query per row.
 * A single-span read needs no key type — it takes the two ids and is done.
 *
 * <p>The two static helpers are the SQL shape of that read, stated once. Both tables are keyed on the same
 * triple, so both hydrations want the same row-constructor predicate, and a second hand-rolled copy is how
 * one of them would end up concatenating the handle in SQL and losing the index on the largest table in the
 * schema.
 */
public record SpanKey(String traceId, String spanId) {

    /** The producer handle {@code "<trace_id>:<span_id>"} — the id a search row carries and a caller passes back. */
    public String handle() {
        return traceId + ':' + spanId;
    }

    /**
     * The row-constructor predicate for {@code keyCount} keys:
     * {@code (trace_id, span_id) IN ((:k0t, :k0s), …)}. Index-eligible on the primary-key prefix; the column
     * names are trusted caller-supplied constants (the two tables spell the span id differently — {@code id}
     * on {@code span}, {@code span_id} on {@code span_payload}) and every value is bound by {@link #bind}.
     *
     * <p>Zero keys yields {@code FALSE} rather than an empty {@code IN ()}, which is a syntax error. Callers
     * should still short-circuit on an empty list — matching no rows is worth no round trip — but a
     * predicate that cannot be malformed is worth more than one that relies on them remembering.
     */
    static String tupleIn(String traceColumn, String spanColumn, int keyCount) {
        if (keyCount == 0) {
            return "FALSE";
        }
        List<String> tuples = new ArrayList<>(keyCount);
        for (int i = 0; i < keyCount; i++) {
            tuples.add("(:k" + i + "t, :k" + i + "s)");
        }
        return "(" + traceColumn + ", " + spanColumn + ") IN (" + String.join(", ", tuples) + ")";
    }

    /** Bind the values {@link #tupleIn} left as placeholders, in the same order. */
    static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, List<SpanKey> keys) {
        JdbcClient.StatementSpec bound = spec;
        for (int i = 0; i < keys.size(); i++) {
            bound = bound.param("k" + i + "t", keys.get(i).traceId());
            bound = bound.param("k" + i + "s", keys.get(i).spanId());
        }
        return bound;
    }
}

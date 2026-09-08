// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import ai.tessary.evals.model.ErrorSignature;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * What counts as a failed tool call, and how two failures are recognized as the same kind. Design
 * contract: {@code classifiers/tool_error/PROGRAM.md} §1 and §2.
 *
 * <p><b>This class is the definition.</b> Not a helper beside it — the only expression of it that
 * exists. {@link #SQL_PREDICATE} is interpolated by every reader that counts failures, and
 * {@link #signature} is the only thing that decides whether two messages describe one failure mode.
 * Both are pure, so both are testable without a database, and their tests are the definition's tests.
 *
 * <h2>Why broader than {@code error_type}</h2>
 *
 * <p>Before this, a tool call failed if and only if {@code tool_call.error_type IS NOT NULL}, which
 * the ingest edge writes from exactly one source: the OTLP span status being {@code ERROR}.
 * That misses the most common real failure in an agent product — a framework catches the exception,
 * hands {@code {"error": "no such customer"}} back to the model, and closes the span cleanly. The agent
 * then reasons from a failure nothing in the platform can see. The four sources in {@link Source}
 * exist to close that.
 *
 * <p><b>Every rule is a structural key check</b> — a column, an attribute name, a JSON key. None reads
 * prose, and none may be made to: a definition that matches text stops being deterministic, stops being
 * cheap, and starts needing a jury, which is precisely the L1 cost model this classifier lives inside.
 * An empty result is not a failure, a slow call is not a failure, and an output that merely contains the
 * word "error" is not a failure.
 */
public final class ToolFailure {

    private ToolFailure() {}

    /**
     * The one definition, as SQL. Requires {@code tool_call} aliased {@code tc}, its carrying SPAN
     * aliased {@code o}, and that span's {@code span_payload} row aliased {@code pl} — every reader joins
     * that way and the alias names are part of this constant's contract.
     *
     * <p>{@code pl} is new and load-bearing: the two producer attributes this rule reads used to live in
     * a jsonb column ON the observation row, and in v2 they live in the payload, so a query that
     * interpolates this constant without joining {@code span_payload} fails outright at
     * {@code column o.attributes does not exist} rather than quietly under-reporting.
     *
     * <p><b>Read at query time and deliberately not materialized into a column.</b> Two reasons, and the
     * second is the one that matters:
     *
     * <ul>
     *   <li>55k tool calls already exist, written under the narrow rule. Broadening ingest plus a
     *       backfill would express this rule <em>twice</em> — once in Java at the write site, once in the
     *       migration's SQL — and nothing would keep the two in step.
     *   <li>If that backfill were wrong or skipped, the failure rate would <b>step at the deploy
     *       boundary</b>, which is indistinguishable from the regression this detector exists to catch.
     *       A definition change that looks like a detection is the worst possible failure here.
     * </ul>
     *
     * <p>The cost is a jsonb check over the page being scanned — bounded, since the sweep reads at most
     * {@code PAGE_SIZE} traces at a time, and the cheap column tests are ordered first so Postgres
     * short-circuits before it touches {@code result} on the overwhelming majority of rows. If volume
     * later makes that hurt, materializing it is a pure optimization <em>because</em> the rule already
     * lives in one place; doing it the other way round is what cannot be undone.
     */
    public static final String SQL_PREDICATE = """
            (tc.error_type IS NOT NULL
             OR tc.is_error IS TRUE
             OR o.error_type IS NOT NULL
             OR pl.attributes ->> 'error.type' IS NOT NULL
             OR pl.attributes ->> 'exception.type' IS NOT NULL
             OR (tc.result IS NOT NULL
                 AND jsonb_typeof(tc.result) = 'object'
                 AND (tc.result @> '{"isError": true}'::jsonb
                      OR (jsonb_exists(tc.result, 'error')
                          AND jsonb_typeof(tc.result -> 'error') <> 'null'))))""";

    /**
     * Which of the four rules recognized a failure. Persisted inside the finding's evidence, so these
     * wire names are never renamed.
     *
     * <p>Carried because the four are not equally trustworthy and a reader deserves to know which one
     * fired: {@link #SPAN_STATUS} is the sender stating an error outright, while {@link #RESULT_ERROR} is
     * this platform reading a convention. A partner whose numbers jump the day this ships is owed the
     * ability to see that the new failures are all one source.
     */
    public enum Source {
        /** Rule 1: the OTLP span status was ERROR. {@code tool_call.error_type} / {@code is_error}. */
        SPAN_STATUS("span_status"),

        /** Rule 2: an OTel {@code error.type} attribute. Semconv's own error signal; status may be unset. */
        ERROR_TYPE_ATTR("error_type_attr"),

        /** Rule 3: a recorded {@code exception.type} attribute. See the class note on span events. */
        EXCEPTION_ATTR("exception_attr"),

        /** Rule 4: the result declared itself an error — MCP's {@code isError}, or a top-level {@code error}. */
        RESULT_ERROR("result_error");

        private final String wire;

        Source(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * One failure, recognized. {@code signature} is what groups it with its kin; {@code source} is which
     * rule saw it.
     */
    public record Recognized(Source source, String signature) {}

    /** The signature a failure carrying no usable message at all gets, so it still groups with its kin. */
    public static final String UNDESCRIBED = ErrorSignature.UNDESCRIBED;

    /**
     * Longest signature kept. Bounded because signatures are map keys inside a persisted evidence blob,
     * and a stack trace pasted into a status message would otherwise be one key.
     */
    static final int MAX_SIGNATURE_LENGTH = ErrorSignature.MAX_SIGNATURE_LENGTH;

    /**
     * Collapse one failure message to the pattern it is an instance of.
     *
     * <p><b>The definition now lives in {@link ErrorSignature}, in {@code core}.</b> It moved down a
     * module when the ingest edge started needing it: {@code substrate} derives a short
     * {@code error_type} from a producer's status message when no {@code error.type} attribute is
     * shipped, and {@code substrate} cannot import {@code analysis}. Copying it would have left two
     * definitions of "these two errors are the same kind" free to drift, which is precisely what this
     * class exists to prevent. This method stays because it is how every reader in this package spells
     * it, and its tests are still the definition's tests.
     *
     * <p>Grouping is still not {@code GROUP BY} the raw column: even now that {@code error_type} holds a
     * producer class rather than prose, a sender is free to put an id in it, and the fallback path writes
     * a signature of a status message. Normalizing on read keeps both shapes grouping the same way.
     */
    public static String signature(@Nullable String message) {
        return ErrorSignature.signature(message);
    }

    /**
     * Recognize one failing row, in the same rule order {@link #SQL_PREDICATE} tests.
     *
     * <p>The SQL selects the rows and this assigns the taxonomy, rather than the query returning a
     * computed source column: the ordering is a product decision ({@link Source#SPAN_STATUS} outranks a
     * read convention when a row satisfies both) and it belongs somewhere a test can reach it without a
     * database.
     *
     * @return null when no rule matches, which for a row the predicate selected means the row changed
     *     under the read — the caller drops it rather than counting a failure it cannot describe.
     */
    public static @Nullable Recognized recognize(
            @Nullable String errorType,
            boolean isError,
            @Nullable String errorTypeAttr,
            @Nullable String exceptionAttr,
            @Nullable JsonNode result) {
        if (errorType != null && !errorType.isBlank()) {
            return new Recognized(Source.SPAN_STATUS, signature(errorType));
        }
        // is_error without a message: a real failure the sender described only as a boolean.
        if (isError && errorTypeAttr == null && exceptionAttr == null) {
            return new Recognized(Source.SPAN_STATUS, UNDESCRIBED);
        }
        if (errorTypeAttr != null && !errorTypeAttr.isBlank()) {
            // The attribute's VALUE is already a type name ("OrderServiceTimeout"), not a message, so it
            // signatures to itself in the ordinary case. Normalized anyway — senders put ids in it.
            return new Recognized(Source.ERROR_TYPE_ATTR, signature(errorTypeAttr));
        }
        if (exceptionAttr != null && !exceptionAttr.isBlank()) {
            return new Recognized(Source.EXCEPTION_ATTR, signature(exceptionAttr));
        }
        String declared = resultError(result);
        if (declared != null) return new Recognized(Source.RESULT_ERROR, signature(declared));
        if (isError) return new Recognized(Source.SPAN_STATUS, UNDESCRIBED);
        return null;
    }

    /**
     * The error a result payload declares about itself, or null if it declares none.
     *
     * <p>Two conventions, both structural. MCP answers with {@code {"isError": true, ...}}; the wider
     * convention is a non-null top-level {@code error}. A textual error is read for its <em>message</em>
     * so the signature can group it; an object one falls back to its own {@code message}/{@code code}
     * field, and to the bare marker when it has neither. What is never done is scanning the payload for
     * the word "error" — see the class comment.
     */
    static @Nullable String resultError(@Nullable JsonNode result) {
        if (result == null || !result.isObject()) return null;
        JsonNode error = result.get("error");
        if (error != null && !error.isNull()) {
            if (error.isTextual()) return error.asText();
            if (error.isObject()) {
                for (String field : new String[] {"message", "code", "type"}) {
                    JsonNode v = error.get(field);
                    if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
                }
            }
            return "result declared an error";
        }
        JsonNode isError = result.get("isError");
        if (isError != null && isError.isBoolean() && isError.asBoolean()) {
            // MCP puts the human-readable reason in the content block beside the flag, not in it.
            JsonNode content = result.get("content");
            if (content != null && content.isTextual() && !content.asText().isBlank()) return content.asText();
            return "tool result reported isError";
        }
        return null;
    }
}

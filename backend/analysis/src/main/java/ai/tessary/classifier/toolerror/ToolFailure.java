// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.model.ErrorSignature;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * The single definition of a failed tool call: {@link #SQL_PREDICATE} for every reader that counts, {@link #signature}
 * for grouping. Broader than {@code error_type} because frameworks often return {@code {"error": ...}} on a cleanly
 * closed span. Every rule is a structural key check; none reads prose, or it would stop being deterministic and cheap.
 */
public final class ToolFailure {

    private ToolFailure() {}

    /**
     * Requires aliases {@code tc} (tool_call), {@code o} (its span) and {@code pl} (span_payload). Read at query time,
     * not materialized: a backfill would duplicate the rule in SQL, and a wrong one would step the failure rate at the
     * deploy boundary, indistinguishable from a real regression. Cheap column tests come first so Postgres
     * short-circuits before reading {@code result}.
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
     * Persisted in finding evidence, so wire names are never renamed. Carried because the rules are not equally
     * trustworthy.
     */
    public enum Source {
        SPAN_STATUS("span_status"),

        ERROR_TYPE_ATTR("error_type_attr"),

        EXCEPTION_ATTR("exception_attr"),

        RESULT_ERROR("result_error");

        private final String wire;

        Source(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public record Recognized(Source source, String signature) {}

    public static final String UNDESCRIBED = ErrorSignature.UNDESCRIBED;

    /**
     * Delegates to {@link ErrorSignature} in {@code core}, which {@code substrate} also needs, so there is one
     * definition. Normalized on read because senders put ids in {@code error_type}.
     */
    public static String signature(@Nullable String message) {
        return ErrorSignature.signature(message);
    }

    /**
     * Ordering is a product decision, so it lives here where a test can reach it. Null means the row changed under the
     * read; the caller drops it.
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
            // Normalized even though the value is usually a type name: senders put ids in it.
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
     * Two structural conventions: MCP's {@code isError}, or a non-null top-level {@code error}. Never scan the payload
     * for the word "error".
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

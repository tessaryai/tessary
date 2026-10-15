// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps OpenLLMetry/Traceloop framework spans ({@code traceloop.span.kind}, {@code traceloop.entity.*}) to canonical
 * {@code gen_ai.*} for foreign senders; otherwise they land kind-unknown with null I/O. Dormant without a
 * {@code traceloop.*} marker, and never overrides a native {@code gen_ai.*} value.
 */
public final class TraceloopNormalizer {

    public static final String SPAN_KIND = "traceloop.span.kind";
    public static final String ENTITY_INPUT = "traceloop.entity.input";
    public static final String ENTITY_OUTPUT = "traceloop.entity.output";

    private TraceloopNormalizer() {}

    /**
     * {@code task} has no gen_ai value and maps to {@code invoke_workflow}, as {@link OpenInferenceNormalizer} maps
     * CHAIN. Unknown or blank is {@code null}.
     */
    public static @Nullable String operationName(@Nullable Object spanKind) {
        if (spanKind == null) {
            return null;
        }
        return switch (spanKind.toString().trim().toLowerCase(Locale.ROOT)) {
            case "llm" -> GenAiAttributes.OP_CHAT;
            case "agent" -> GenAiAttributes.OP_INVOKE_AGENT;
            case "tool" -> GenAiAttributes.OP_EXECUTE_TOOL;
            case "workflow", "task" -> GenAiAttributes.OP_INVOKE_WORKFLOW;
            default -> null;
        };
    }

    public static @Nullable String input(Map<String, ? extends @Nullable Object> attrs) {
        return value(attrs, ENTITY_INPUT);
    }

    public static @Nullable String output(Map<String, ? extends @Nullable Object> attrs) {
        return value(attrs, ENTITY_OUTPUT);
    }

    private static @Nullable String value(Map<String, ? extends @Nullable Object> attrs, String key) {
        Object v = attrs.get(key);
        return v == null ? null : v.toString();
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes the OpenLLMetry/Traceloop framework-span convention into canonical {@code gen_ai.*} at the
 * ingest edge — the foreign-sender fallback peer to {@link OpenInferenceNormalizer}.
 *
 * <p>OpenLLMetry emits native {@code gen_ai.*} for raw model calls but its own {@code traceloop.*}
 * convention for framework / agent / tool / workflow steps: {@code traceloop.span.kind}
 * ({@code workflow}/{@code task}/{@code agent}/{@code tool}/{@code llm}) + {@code traceloop.entity.input}
 * / {@code traceloop.entity.output}, with no {@code gen_ai.operation.name} and no {@code gen_ai.*.messages}.
 * Without this mapping such spans land kind-unknown with null I/O.
 *
 * <p>Producers that emit canonical gen_ai already do this, so for canonicalized traffic
 * this is redundant — it exists for <em>foreign</em> senders (a raw OpenLLMetry app, an older/foreign
 * producer). It is
 * dormant unless a {@code traceloop.*} marker is present, and never overrides a value the native
 * {@code gen_ai.*} read already produced.
 */
public final class TraceloopNormalizer {

    /** {@code traceloop.span.kind} — Traceloop's operation discriminator (workflow/task/agent/tool/llm). */
    public static final String SPAN_KIND = "traceloop.span.kind";
    /** {@code traceloop.entity.input} — a framework step's generic (non-message) input payload. */
    public static final String ENTITY_INPUT = "traceloop.entity.input";
    /** {@code traceloop.entity.output} — a framework step's generic (non-message) output payload. */
    public static final String ENTITY_OUTPUT = "traceloop.entity.output";

    private TraceloopNormalizer() {}

    /** True when the attribute map carries any Traceloop framework-span signal we know how to normalize. */
    public static boolean isTraceloop(@Nullable Map<String, ? extends @Nullable Object> attrs) {
        if (attrs == null) {
            return false;
        }
        return attrs.containsKey(SPAN_KIND) || attrs.containsKey(ENTITY_INPUT) || attrs.containsKey(ENTITY_OUTPUT);
    }

    /**
     * Map a {@code traceloop.span.kind} value to the canonical {@code gen_ai.operation.name}. {@code task}
     * (a coordinated sub-step) has no gen_ai registry value; it maps to {@code invoke_workflow}, the closest
     * canonical kind (mirroring {@link OpenInferenceNormalizer}'s CHAIN mapping). Unknown/blank → {@code null}.
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

    /** The framework step's generic input payload, as a string; {@code null} when absent. */
    public static @Nullable String input(@Nullable Map<String, ? extends @Nullable Object> attrs) {
        return value(attrs, ENTITY_INPUT);
    }

    /** The framework step's generic output payload, as a string; {@code null} when absent. */
    public static @Nullable String output(@Nullable Map<String, ? extends @Nullable Object> attrs) {
        return value(attrs, ENTITY_OUTPUT);
    }

    private static @Nullable String value(@Nullable Map<String, ? extends @Nullable Object> attrs, String key) {
        if (attrs == null) {
            return null;
        }
        Object v = attrs.get(key);
        return v == null ? null : v.toString();
    }
}

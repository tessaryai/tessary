// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sandbox.AgentSpanTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One launcher session event ({@code sandbox-runner/README.md}). {@code seq} starts at 1 and rises by one per event.
 */
public sealed interface SessionEvent {

    long seq();

    record Text(long seq, String delta) implements SessionEvent {}

    /** @param server the MCP server's configured name, null for the agent's own tools */
    record ToolStart(long seq, String callId, @Nullable String server, String tool, String inputSummary)
            implements SessionEvent {}

    record ToolEnd(long seq, String callId, String resultSummary, long bytes, long durationMs, boolean ok)
            implements SessionEvent {}

    /** The reply ended; its usage is what it spent. */
    record MessageEnd(long seq, AgentSpanTelemetry.AgentUsage usage, EndReason reason) implements SessionEvent {}

    /** @param kind {@code start}, {@code agent} or {@code opencode_exit} */
    record Failure(long seq, String kind, String detail) implements SessionEvent {}

    /**
     * Always the last event. {@link CloseReason#UNKNOWN} is the client's own, for a 404: {@code seq} is then the
     * caller's {@code afterSeq}.
     */
    record Closed(long seq, CloseReason reason) implements SessionEvent {}

    enum EndReason {
        DONE,
        ABORTED,
        LIMIT
    }

    enum CloseReason {
        IDLE,
        STOPPED,
        FAILED,
        UNKNOWN
    }

    /**
     * Empty for an unknown type. @throws TessaryException {@code AgentRunError.BAD_OUTPUT} for data that does not fit;
     * the message names the type and field, never the data
     */
    static Optional<SessionEvent> parse(ObjectMapper mapper, long seq, String type, JsonNode data) {
        return Optional.ofNullable(
                switch (type) {
                    case "text" -> new Text(seq, text(type, data, "delta"));
                    case "tool_start" ->
                        new ToolStart(
                                seq,
                                text(type, data, "call_id"),
                                data.path("server").isNull() ? null : text(type, data, "server"),
                                text(type, data, "tool"),
                                text(type, data, "input_summary"));
                    case "tool_end" ->
                        new ToolEnd(
                                seq,
                                text(type, data, "call_id"),
                                text(type, data, "result_summary"),
                                number(type, data, "bytes"),
                                number(type, data, "duration_ms"),
                                flag(type, data, "ok"));
                    case "message_end" -> new MessageEnd(seq, usage(mapper, type, data), endReason(type, data));
                    case "error" -> new Failure(seq, text(type, data, "kind"), text(type, data, "detail"));
                    case "closed" -> new Closed(seq, closeReason(type, data));
                    default -> null;
                });
    }

    private static String text(String type, JsonNode data, String field) {
        JsonNode v = data.path(field);
        if (!v.isTextual()) throw unfit(type, field);
        return v.textValue();
    }

    private static long number(String type, JsonNode data, String field) {
        JsonNode v = data.path(field);
        if (!v.isIntegralNumber()) throw unfit(type, field);
        return v.longValue();
    }

    private static boolean flag(String type, JsonNode data, String field) {
        JsonNode v = data.path(field);
        if (!v.isBoolean()) throw unfit(type, field);
        return v.booleanValue();
    }

    private static AgentSpanTelemetry.AgentUsage usage(ObjectMapper mapper, String type, JsonNode data) {
        if (!data.path("usage").isObject()) throw unfit(type, "usage");
        return Objects.requireNonNull(AgentSpanTelemetry.parseUsage(mapper, data.toString()));
    }

    private static EndReason endReason(String type, JsonNode data) {
        return switch (text(type, data, "reason")) {
            case "done" -> EndReason.DONE;
            case "aborted" -> EndReason.ABORTED;
            case "limit" -> EndReason.LIMIT;
            default -> throw unfit(type, "reason");
        };
    }

    private static CloseReason closeReason(String type, JsonNode data) {
        return switch (text(type, data, "reason")) {
            case "idle" -> CloseReason.IDLE;
            case "stopped" -> CloseReason.STOPPED;
            case "failed" -> CloseReason.FAILED;
            default -> throw unfit(type, "reason");
        };
    }

    private static TessaryException unfit(String type, String field) {
        return new TessaryException(
                AgentRunError.BAD_OUTPUT,
                String.format(Locale.ROOT, "a session %s event without a valid %s", type, field));
    }
}

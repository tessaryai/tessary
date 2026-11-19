// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sandbox.AgentSpanTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * What a fix session's agent changed in its clone since {@code baseSha}, sent once per reply just before its
     * {@link MessageEnd}. With {@code overLimit} set, {@code files} is empty: a partial change is never sent.
     *
     * @param overLimit {@code files} or {@code bytes} when the change was over the sandbox's cap, else null
     */
    record Patch(
            long seq,
            String baseSha,
            List<PatchFile> files,
            List<Refusal> refused,
            @Nullable String overLimit) implements SessionEvent {

        public Patch {
            files = List.copyOf(files);
            refused = List.copyOf(refused);
        }
    }

    /**
     * One changed path: a new or changed file has a git {@code mode} ({@code 100644}, {@code 100755} or {@code 120000}
     * for a symlink) and its content; a deleted one has neither.
     */
    record PatchFile(
            String path, @Nullable String mode, @Nullable String contentBase64) {

        private static final Set<String> MODES = Set.of("100644", "100755", "120000");

        public boolean deleted() {
            return contentBase64 == null;
        }

        /** @throws IllegalStateException for a deleted file */
        public byte[] content() {
            if (contentBase64 == null) throw new IllegalStateException(path + " was deleted");
            return Base64.getDecoder().decode(contentBase64);
        }
    }

    /**
     * A path the sandbox left out of the patch.
     *
     * @param reason {@code git_dir}, {@code quarantined}, {@code submodule}, {@code mode} or {@code symlink_escape}
     */
    record Refusal(String path, String reason) {}

    /** The reply ended; its usage is what it spent. */
    record MessageEnd(long seq, AgentSpanTelemetry.AgentUsage usage, EndReason reason) implements SessionEvent {}

    /**
     * @param kind {@code start}, {@code agent} or {@code opencode_exit}, or for a model provider that stopped the reply
     * {@code provider_rejected}, {@code request_refused} or {@code provider_unavailable}
     */
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
                    case "patch" -> patch(seq, type, data);
                    case "message_end" -> new MessageEnd(seq, usage(mapper, type, data), endReason(type, data));
                    case "error" -> new Failure(seq, text(type, data, "kind"), text(type, data, "detail"));
                    case "closed" -> new Closed(seq, closeReason(type, data));
                    default -> null;
                });
    }

    private static Patch patch(long seq, String type, JsonNode data) {
        String baseSha = text(type, data, "base_sha");
        if (!data.path("files").isArray()) throw unfit(type, "files");
        if (!data.path("refused").isArray()) throw unfit(type, "refused");
        List<PatchFile> files = new ArrayList<>();
        for (JsonNode file : data.path("files")) files.add(patchFile(type, file));
        List<Refusal> refused = new ArrayList<>();
        for (JsonNode r : data.path("refused"))
            refused.add(new Refusal(text(type, r, "path"), text(type, r, "reason")));
        JsonNode over = data.path("over_limit");
        String overLimit = over.isNull() || over.isMissingNode() ? null : text(type, data, "over_limit");
        if (overLimit != null && !files.isEmpty()) throw unfit(type, "over_limit");
        return new Patch(seq, baseSha, files, refused, overLimit);
    }

    private static PatchFile patchFile(String type, JsonNode file) {
        String path = text(type, file, "path");
        if (file.path("deleted").booleanValue()) return new PatchFile(path, null, null);
        String mode = text(type, file, "mode");
        if (!PatchFile.MODES.contains(mode)) throw unfit(type, "mode");
        String content = text(type, file, "content_base64");
        try {
            Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            throw unfit(type, "content_base64");
        }
        return new PatchFile(path, mode, content);
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One timeline event: a span, or a message inside one. @param id {@code <trace_id>/<span_id>}, plus {@code #user:<n>}
 * or {@code #reply:<n>} for a message @param kind the canonical span kind ({@code mcp} folded into {@code tool}), or
 * {@link #USER_MESSAGE} or {@link #ASSISTANT_REPLY} @param turn the trace id: the turn is the trace @param at a span's
 * end, a user message's carrier's start, a reply's writer's end @param spanId the span itself, a user message's first
 * carrier, or a reply's writer @param parentId a span's producer parent, or a message's carrying span @param agentId
 * the nearest enclosing {@code agent} span, never the span itself @param bytes UTF-8 size of the event's JSON without
 * {@code position} and {@code bytes}
 */
public record Event(
        String id,
        String kind,
        int position,
        String turn,
        @JsonSerialize(using = ToStringSerializer.class) Instant at,
        @JsonProperty("call_site_id") @Nullable String callSiteId,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("span_id") String spanId,
        @JsonProperty("parent_id") @Nullable String parentId,
        @JsonProperty("parent_kind") @Nullable String parentKind,
        @JsonProperty("ancestor_kinds") List<String> ancestorKinds,
        int depth,
        @JsonProperty("agent_id") @Nullable String agentId,
        ObjectNode data,
        long bytes) {

    public static final String USER_MESSAGE = "user_message";
    public static final String ASSISTANT_REPLY = "assistant_reply";

    public Event {
        ancestorKinds = List.copyOf(ancestorKinds);
    }

    Event withBytes(long bytes) {
        return new Event(
                id,
                kind,
                position,
                turn,
                at,
                callSiteId,
                traceId,
                spanId,
                parentId,
                parentKind,
                ancestorKinds,
                depth,
                agentId,
                data,
                bytes);
    }

    Event withPosition(int position) {
        return new Event(
                id,
                kind,
                position,
                turn,
                at,
                callSiteId,
                traceId,
                spanId,
                parentId,
                parentKind,
                ancestorKinds,
                depth,
                agentId,
                data,
                bytes);
    }
}

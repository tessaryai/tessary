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
 * One event of a session timeline: a span, or a message inside a span.
 *
 * @param id a span event's span id; a message event's {@code <span_id>#user:<n>} or {@code <span_id>#reply:<n>}
 * @param kind the span's canonical kind ({@code mcp} folded into {@code tool}), or {@link #USER_MESSAGE} or {@link
 *     #ASSISTANT_REPLY}
 * @param position the 0-based index in the timeline
 * @param turn the trace id: the turn is the trace
 * @param at the ordering time: a span's end, a user message's carrying span's start, a reply's writer's end
 * @param spanId the span the event came from: the span itself, a user message's first carrier, a reply's writer
 * @param parentId a span event's producer parent span id; a message event's carrying span
 * @param ancestorKinds the event kinds from the root down to the parent
 * @param agentId the nearest enclosing {@code agent} span, never the span itself
 * @param bytes the UTF-8 size of the event's JSON form without {@code position} and {@code bytes}
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import ai.tessary.classifier.timeline.DialogueMessages.DialogueMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link SessionTimeline} of a session's spans: one event per complete span, plus the user messages and
 * assistant replies inside the spans, in one order.
 *
 * <p>Each trace is derived on its own: the tree facts, the person-facing spans, the user messages and the replies of a
 * trace look only inside that trace. So a caller can load whole traces and derive them one by one.
 */
public final class SessionTimelineBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AGENT = "agent";
    private static final String LLM = "llm";
    private static final String TOOL = "tool";
    private static final String MCP = "mcp";
    private static final String STEP = "step";
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final Set<String> SYSTEM_ROLES = Set.of("system", "developer");
    private static final String SETTINGS_PREFIX = "gen_ai.request.";
    private static final String MODEL_SETTING = "gen_ai.request.model";
    // Only span events carry it: a span event sorts after the user messages its span carries on one instant.
    private static final int SPAN_ORDINAL = Integer.MAX_VALUE;

    private SessionTimelineBuilder() {}

    /** An event of one trace before ordering: {@code ordinal} breaks the last tie, {@code writerSpanId} is a reply's. */
    record Derived(Event event, @Nullable String writerSpanId, int ordinal) {}

    /** The whole timeline of the given spans (any number of traces of one session). */
    public static SessionTimeline build(String projectId, String sessionId, List<TimelineSpan> spans) {
        Map<String, List<TimelineSpan>> byTrace = new LinkedHashMap<>();
        for (TimelineSpan span : spans) {
            byTrace.computeIfAbsent(span.traceId(), k -> new ArrayList<>()).add(span);
        }
        List<Derived> derived = new ArrayList<>();
        for (List<TimelineSpan> trace : byTrace.values()) {
            derived.addAll(derive(trace));
        }
        return assemble(projectId, sessionId, derived, false);
    }

    /** One trace's events, not yet ordered or numbered. */
    static List<Derived> derive(List<TimelineSpan> oneTrace) {
        return new TraceDerivation(oneTrace).derive();
    }

    /**
     * Orders the events of the loaded traces and numbers them: by time, the deeper event first on a tie, then trace,
     * span and ordinal; each reply right after the span event of its writer.
     */
    static SessionTimeline assemble(String projectId, String sessionId, List<Derived> events, boolean truncated) {
        Map<List<String>, List<Derived>> repliesByWriter = new HashMap<>();
        List<Derived> others = new ArrayList<>();
        for (Derived d : events) {
            String writer = d.writerSpanId();
            if (writer == null) {
                others.add(d);
            } else {
                repliesByWriter
                        .computeIfAbsent(List.of(d.event().traceId(), writer), k -> new ArrayList<>())
                        .add(d);
            }
        }
        others.sort(Comparator.comparing((Derived d) -> d.event().at())
                .thenComparing(d -> d.event().depth(), Comparator.reverseOrder())
                .thenComparing(d -> d.event().traceId())
                .thenComparing(d -> d.event().spanId())
                .thenComparingInt(Derived::ordinal));
        List<Event> ordered = new ArrayList<>(events.size());
        for (Derived d : others) {
            Event event = d.event();
            ordered.add(event.withPosition(ordered.size()));
            if (d.ordinal() != SPAN_ORDINAL) continue;
            List<Derived> replies = repliesByWriter.get(List.of(event.traceId(), event.spanId()));
            if (replies == null) continue;
            replies.sort(Comparator.comparingInt(Derived::ordinal));
            for (Derived reply : replies) {
                ordered.add(reply.event().withPosition(ordered.size()));
            }
        }
        return new SessionTimeline(projectId, sessionId, ordered, truncated);
    }

    /** The tree facts of a span, from the spans of its trace that are loaded. */
    private record Facts(
            @Nullable String parentKind,
            List<String> ancestorKinds,
            int depth,
            @Nullable String agentId) {}

    private static final class TraceDerivation {

        private final List<TimelineSpan> spans;
        private final Map<String, TimelineSpan> byId = new HashMap<>();
        private final Map<String, Facts> facts = new HashMap<>();
        private final List<Derived> out = new ArrayList<>();

        TraceDerivation(List<TimelineSpan> spans) {
            this.spans = spans;
            for (TimelineSpan span : spans) {
                byId.put(span.spanId(), span);
            }
            for (TimelineSpan span : spans) {
                facts.put(span.spanId(), treeFacts(span));
            }
        }

        List<Derived> derive() {
            for (TimelineSpan span : spans) {
                Instant ended = span.endedAt();
                if (ended == null) continue;
                Facts f = facts(span);
                out.add(new Derived(
                        event(
                                spanEventId(span),
                                eventKind(span),
                                ended,
                                span,
                                span.parentSpanId(),
                                f.parentKind(),
                                f.ancestorKinds(),
                                f.depth(),
                                f.agentId(),
                                spanData(span)),
                        null,
                        SPAN_ORDINAL));
            }
            boolean detached = !spans.isEmpty() && spans.get(0).parentTraceId() != null;
            if (!detached) {
                List<TimelineSpan> facing = spans.stream()
                        .filter(this::isPersonFacing)
                        .sorted(Comparator.comparing(TimelineSpan::startedAt)
                                .thenComparingInt(s -> facts(s).depth())
                                .thenComparing(TimelineSpan::spanId))
                        .toList();
                userMessages(facing);
                replies(facing);
            }
            return out;
        }

        /**
         * The new user messages of each person-facing span, in start order. A text a span carries no more times than
         * earlier spans of the trace did is a resend, so it is not a new event; {@code n} counts before that check.
         */
        private void userMessages(List<TimelineSpan> facing) {
            Map<String, Integer> emitted = new HashMap<>();
            for (TimelineSpan carrier : facing) {
                List<DialogueMessage> users =
                        DialogueMessages.afterLastAssistant(DialogueMessages.read(carrier.input(), USER)).stream()
                                .filter(m -> USER.equals(m.role()) && m.dialogue())
                                .toList();
                Map<String, Integer> inSpan = new HashMap<>();
                for (int n = 0; n < users.size(); n++) {
                    String text = users.get(n).text();
                    int occurrence = inSpan.merge(text, 1, Integer::sum);
                    if (occurrence <= emitted.getOrDefault(text, 0)) continue;
                    ObjectNode data =
                            MAPPER.createObjectNode().put("text", text).put("from_person", true);
                    out.add(new Derived(
                            messageEvent(
                                    carrier,
                                    spanEventId(carrier) + "#user:" + n,
                                    Event.USER_MESSAGE,
                                    carrier.startedAt(),
                                    data),
                            null,
                            n));
                }
                inSpan.forEach((text, count) -> emitted.merge(text, count, Math::max));
            }
        }

        /**
         * The replies of the turn. When a top agent span returned text, the agent output is the authority: an
         * {@code llm} text is a reply when the agent returned it, or when another person-facing {@code llm} call
         * received it as an assistant message more times than the writer's own input holds it (the agent loop carried
         * the new text on, not only the old history); an agent text no {@code llm} wrote is a reply of the agent span.
         * Without agent text, every text of a person-facing {@code llm} span is a reply. One text is one reply per
         * turn. No clock decides a reply, because the next call can start in the instant the last one ended.
         */
        private void replies(List<TimelineSpan> facing) {
            Comparator<TimelineSpan> endOrder = Comparator.comparing(
                            (TimelineSpan s) -> Objects.requireNonNull(s.endedAt()))
                    .thenComparing(s -> facts(s).depth(), Comparator.reverseOrder())
                    .thenComparing(TimelineSpan::spanId);
            List<TimelineSpan> topAgents = spans.stream()
                    .filter(s -> s.endedAt() != null && isTopAgent(s))
                    .sorted(endOrder)
                    .toList();
            Set<String> agentTexts = new HashSet<>();
            for (TimelineSpan agent : topAgents) {
                agentTexts.addAll(assistantTexts(agent.output()));
            }
            List<TimelineSpan> facingLlms =
                    facing.stream().filter(s -> LLM.equals(eventKind(s))).toList();
            Set<String> replied = new HashSet<>();
            for (TimelineSpan writer : facingLlms.stream()
                    .filter(s -> s.endedAt() != null)
                    .sorted(endOrder)
                    .toList()) {
                List<String> texts = assistantTexts(writer.output());
                for (int n = 0; n < texts.size(); n++) {
                    String text = texts.get(n);
                    boolean shown =
                            agentTexts.isEmpty() || agentTexts.contains(text) || carriedOn(facingLlms, writer, text);
                    if (shown && replied.add(text)) {
                        out.add(reply(writer, n, text));
                    }
                }
            }
            for (TimelineSpan agent : topAgents) {
                List<String> texts = assistantTexts(agent.output());
                for (int n = 0; n < texts.size(); n++) {
                    if (replied.add(texts.get(n))) {
                        out.add(reply(agent, n, texts.get(n)));
                    }
                }
            }
        }

        private boolean carriedOn(List<TimelineSpan> llms, TimelineSpan writer, String text) {
            long history = timesReceived(writer, text);
            for (TimelineSpan other : llms) {
                if (!other.spanId().equals(writer.spanId()) && timesReceived(other, text) > history) return true;
            }
            return false;
        }

        private static long timesReceived(TimelineSpan span, String text) {
            return DialogueMessages.read(span.input(), USER).stream()
                    .filter(m -> ASSISTANT.equals(m.role())
                            && m.hasText()
                            && m.text().equals(text))
                    .count();
        }

        private Derived reply(TimelineSpan writer, int n, String text) {
            ObjectNode data = MAPPER.createObjectNode().put("text", text);
            Instant ended = Objects.requireNonNull(writer.endedAt());
            return new Derived(
                    messageEvent(writer, spanEventId(writer) + "#reply:" + n, Event.ASSISTANT_REPLY, ended, data),
                    writer.spanId(),
                    n);
        }

        private Event messageEvent(TimelineSpan carrier, String id, String kind, Instant at, ObjectNode data) {
            Facts f = facts(carrier);
            String carrierKind = eventKind(carrier);
            List<String> ancestors = new ArrayList<>(f.ancestorKinds());
            ancestors.add(carrierKind);
            String agentId = AGENT.equals(carrierKind) ? carrier.spanId() : f.agentId();
            return event(id, kind, at, carrier, carrier.spanId(), carrierKind, ancestors, f.depth() + 1, agentId, data);
        }

        /** An {@code agent} span with no {@code agent} ancestor: the agent the person talks to. */
        private boolean isTopAgent(TimelineSpan span) {
            return AGENT.equals(eventKind(span)) && facts(span).agentId() == null;
        }

        /** A top agent span, or an {@code llm} span whose nearest agent is a top agent or that has no agent. */
        private boolean isPersonFacing(TimelineSpan span) {
            if (isTopAgent(span)) return true;
            if (!LLM.equals(eventKind(span))) return false;
            String agentId = facts(span).agentId();
            if (agentId == null) return true;
            TimelineSpan agent = byId.get(agentId);
            return agent != null && isTopAgent(agent);
        }

        private Facts facts(TimelineSpan span) {
            return Objects.requireNonNull(facts.get(span.spanId()), span.spanId());
        }

        /** Walks the producer's parent links inside the trace; a parent that is not loaded ends the walk. */
        private Facts treeFacts(TimelineSpan span) {
            List<TimelineSpan> up = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            visited.add(span.spanId());
            String parentId = span.parentSpanId();
            while (parentId != null && visited.add(parentId)) {
                TimelineSpan parent = byId.get(parentId);
                if (parent == null) break;
                up.add(parent);
                parentId = parent.parentSpanId();
            }
            Collections.reverse(up);
            List<String> kinds =
                    up.stream().map(SessionTimelineBuilder::eventKind).toList();
            String agentId = null;
            for (TimelineSpan ancestor : up) {
                if (AGENT.equals(eventKind(ancestor))) agentId = ancestor.spanId();
            }
            return new Facts(kinds.isEmpty() ? null : kinds.get(kinds.size() - 1), kinds, up.size(), agentId);
        }
    }

    /** A span id is unique only inside its trace, so the event id carries the trace id too. */
    private static String spanEventId(TimelineSpan span) {
        return span.traceId() + "/" + span.spanId();
    }

    private static String eventKind(TimelineSpan span) {
        return MCP.equals(span.kind()) ? TOOL : span.kind();
    }

    private static Event event(
            String id,
            String kind,
            Instant at,
            TimelineSpan span,
            @Nullable String parentId,
            @Nullable String parentKind,
            List<String> ancestorKinds,
            int depth,
            @Nullable String agentId,
            ObjectNode data) {
        long bytes = measure(
                id,
                kind,
                at,
                span.callSiteId(),
                span.traceId(),
                span.spanId(),
                parentId,
                parentKind,
                ancestorKinds,
                depth,
                agentId,
                data);
        return new Event(
                id,
                kind,
                -1,
                span.traceId(),
                at,
                span.callSiteId(),
                span.traceId(),
                span.spanId(),
                parentId,
                parentKind,
                ancestorKinds,
                depth,
                agentId,
                data,
                bytes);
    }

    /**
     * The one place an event's size is computed: the UTF-8 length of its JSON form without {@code position} and
     * {@code bytes}, so cutting a tail, which renumbers positions, never changes a size.
     */
    private static long measure(
            String id,
            String kind,
            Instant at,
            @Nullable String callSiteId,
            String traceId,
            String spanId,
            @Nullable String parentId,
            @Nullable String parentKind,
            List<String> ancestorKinds,
            int depth,
            @Nullable String agentId,
            ObjectNode data) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("id", id)
                .put("kind", kind)
                .put("turn", traceId)
                .put("at", at.toString())
                .put("call_site_id", callSiteId)
                .put("trace_id", traceId)
                .put("span_id", spanId)
                .put("parent_id", parentId)
                .put("parent_kind", parentKind);
        ArrayNode kinds = node.putArray("ancestor_kinds");
        ancestorKinds.forEach(kinds::add);
        node.put("depth", depth).put("agent_id", agentId).set("data", data);
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static ObjectNode spanData(TimelineSpan span) {
        ObjectNode data = MAPPER.createObjectNode();
        switch (eventKind(span)) {
            case TOOL -> {
                TimelineSpan.Tool tool = span.tool();
                String toolName = tool == null ? null : tool.name();
                data.put("name", toolName == null ? span.name() : toolName)
                        .put("mcp", MCP.equals(span.kind()))
                        .put("status", span.status())
                        .put("error_type", span.errorType());
                data.set("input", parse(tool == null ? span.input() : tool.arguments()));
                data.set("output", parse(tool == null ? span.output() : tool.result()));
            }
            case LLM -> llmData(span, data);
            case STEP -> {
                data.put("name", span.name());
                data.set("input", parse(span.input()));
                data.set("output", parse(span.output()));
            }
            default -> {
                data.put("name", span.name()).put("status", span.status());
                data.set("input", parse(span.input()));
                data.set("output", parse(span.output()));
                data.set("attributes", parse(span.attributes()));
            }
        }
        return data;
    }

    private static void llmData(TimelineSpan span, ObjectNode data) {
        List<DialogueMessage> input = DialogueMessages.read(span.input(), USER);
        data.put("model", span.model());
        data.put(
                "system_prompt",
                joined(input.stream()
                        .filter(m -> SYSTEM_ROLES.contains(m.role()))
                        .map(DialogueMessage::text)
                        .toList()));
        ArrayNode fresh = data.putArray("new_messages");
        for (DialogueMessage m : DialogueMessages.afterLastAssistant(input)) {
            if (!SYSTEM_ROLES.contains(m.role())) fresh.add(m.source());
        }
        List<DialogueMessage> replies = DialogueMessages.read(span.output(), ASSISTANT).stream()
                .filter(m -> ASSISTANT.equals(m.role()))
                .toList();
        ObjectNode output = data.putObject("output");
        output.put("text", joined(replies.stream().map(DialogueMessage::text).toList()));
        ArrayNode toolCalls = output.putArray("tool_calls");
        replies.forEach(m -> m.toolCalls().forEach(toolCalls::add));
        output.put(
                "reasoning",
                joined(replies.stream()
                        .map(DialogueMessage::reasoning)
                        .filter(Objects::nonNull)
                        .toList()));
        ObjectNode settings = data.putObject("settings");
        JsonNode attributes = parse(span.attributes());
        if (attributes.isObject()) {
            attributes.properties().forEach(e -> {
                if (e.getKey().startsWith(SETTINGS_PREFIX) && !MODEL_SETTING.equals(e.getKey())) {
                    settings.set(e.getKey().substring(SETTINGS_PREFIX.length()), e.getValue());
                }
            });
        }
        TimelineSpan.Usage usage = span.usage();
        data.putObject("usage")
                .put("input_tokens", usage.inputTokens())
                .put("output_tokens", usage.outputTokens())
                .put("cache_read_tokens", usage.cacheReadTokens())
                .put("cache_write_tokens", usage.cacheWriteTokens())
                .put("reasoning_tokens", usage.reasoningTokens())
                .put("total_tokens", usage.totalTokens());
    }

    /** The texts of the assistant messages of a column that carry real text, in order. */
    private static List<String> assistantTexts(@Nullable String output) {
        return DialogueMessages.read(output, ASSISTANT).stream()
                .filter(m -> ASSISTANT.equals(m.role()) && m.hasText())
                .map(DialogueMessage::text)
                .toList();
    }

    private static @Nullable String joined(List<String> texts) {
        StringJoiner joiner = new StringJoiner("\n");
        texts.stream().filter(t -> !t.isEmpty()).forEach(joiner::add);
        return joiner.length() == 0 ? null : joiner.toString();
    }

    /** A stored column as JSON: parsed when it is JSON, else a JSON string; null stays null. */
    private static JsonNode parse(@Nullable String raw) {
        if (raw == null) return NullNode.getInstance();
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node == null || node.isMissingNode() ? TextNode.valueOf(raw) : node;
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw);
        }
    }
}

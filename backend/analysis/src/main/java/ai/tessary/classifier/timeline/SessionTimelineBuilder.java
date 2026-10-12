// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import ai.tessary.classifier.timeline.DialogueMessages.DialogueMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    // Writes an event as its JSON form without position and bytes, the form Event#bytes is the size of.
    private static final ObjectMapper SIZER = new ObjectMapper().addMixIn(Event.class, Unsized.class);

    private static final String AGENT = "agent";
    private static final String LLM = "llm";
    private static final String TOOL = "tool";
    private static final String MCP = "mcp";
    private static final String STEP = "step";
    private static final String WORKFLOW = "workflow";
    private static final String HANDOFF = "handoff";
    // The kinds that only group a turn's steps. Any other span, such as a tool, a guardrail or a retrieval, does work
    // for the agent, so a model call inside it is not a call the person talks to.
    private static final Set<String> DIALOGUE_PATH_KINDS = Set.of(LLM, STEP, WORKFLOW, HANDOFF);
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final Set<String> SYSTEM_ROLES = Set.of("system", "developer");
    private static final String SETTINGS_PREFIX = "gen_ai.request.";
    private static final String MODEL_SETTING = "gen_ai.request.model";
    // Only span events carry it: a span event sorts after the user messages its span carries on one instant.
    private static final int SPAN_ORDINAL = Integer.MAX_VALUE;

    private SessionTimelineBuilder() {}

    @JsonIgnoreProperties({"position", "bytes"})
    private interface Unsized {}

    /**
     * An event of one trace before ordering: {@code ordinal} breaks the last tie, {@code writerSpanId} is a reply's,
     * {@code traceLast} is the latest instant of the event's trace, {@code ancestorIds} the loaded spans above the
     * event, root first; a message's end with its carrier.
     */
    record Derived(
            Event event, @Nullable String writerSpanId, int ordinal, Instant traceLast, List<String> ancestorIds) {

        Derived {
            ancestorIds = List.copyOf(ancestorIds);
        }
    }

    /** The whole timeline of the given spans: any number of whole traces of one session, each one settled. */
    public static SessionTimeline build(String projectId, String sessionId, List<TimelineSpan> spans) {
        Map<String, List<TimelineSpan>> byTrace = new LinkedHashMap<>();
        for (TimelineSpan span : spans) {
            byTrace.computeIfAbsent(span.traceId(), k -> new ArrayList<>()).add(span);
        }
        List<Derived> derived = new ArrayList<>();
        for (List<TimelineSpan> trace : byTrace.values()) {
            derived.addAll(derive(trace, true));
        }
        return assemble(projectId, sessionId, derived, false);
    }

    /**
     * One trace's events, not yet ordered or numbered. A trace is {@code settled} when no span of it is on its way, so
     * a span whose parent is not loaded is a root of its own; in a trace that is not settled, such a span carries no
     * message, because its place in the tree, and so whether the person sees it, is not known yet.
     */
    static List<Derived> derive(List<TimelineSpan> oneTrace, boolean settled) {
        return new TraceDerivation(oneTrace, settled).derive();
    }

    /**
     * Orders the events of the loaded traces and numbers them: by time; on a tie the event of the trace that ends first,
     * then the deeper event, then trace, span and ordinal; each reply right after the span event of its writer. A user
     * message is placed at a start ({@code receivedAt}), so in its instant it comes after the span events of its trace
     * that end there, except its carrier's, its carrier's ancestors' and its carrier's descendants', which it comes
     * before.
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
                .thenComparing(Derived::traceLast)
                .thenComparing(d -> d.event().traceId())
                .thenComparing(d -> d.event().depth(), Comparator.reverseOrder())
                .thenComparing(d -> d.event().spanId())
                .thenComparingInt(Derived::ordinal));
        List<Event> ordered = new ArrayList<>(events.size());
        for (Derived d : startsAfterEnds(others)) {
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

    /** The sorted events with each instant of a trace reordered by {@link #placeInstant}. */
    private static List<Derived> startsAfterEnds(List<Derived> sorted) {
        List<Derived> placed = new ArrayList<>(sorted.size());
        int from = 0;
        while (from < sorted.size()) {
            Event first = sorted.get(from).event();
            int to = from + 1;
            while (to < sorted.size()
                    && sorted.get(to).event().at().equals(first.at())
                    && sorted.get(to).event().traceId().equals(first.traceId())) {
                to++;
            }
            placeInstant(sorted.subList(from, to), placed);
            from = to;
        }
        return placed;
    }

    /**
     * The events of one instant of one trace, in sorted order, except that each user message moves to just before the
     * first span event in its line, or after them all: work that ended in the instant a message's span starts came
     * before the message, while the spans in its line come after it, as a parent comes after its child.
     */
    private static void placeInstant(List<Derived> instant, List<Derived> placed) {
        List<Derived> waiting = new ArrayList<>();
        for (Derived d : instant) {
            if (Event.USER_MESSAGE.equals(d.event().kind())) waiting.add(d);
        }
        if (waiting.isEmpty()) {
            placed.addAll(instant);
            return;
        }
        for (Derived d : instant) {
            if (Event.USER_MESSAGE.equals(d.event().kind())) continue;
            for (Iterator<Derived> it = waiting.iterator(); it.hasNext(); ) {
                Derived message = it.next();
                if (inLine(d, message)) {
                    placed.add(message);
                    it.remove();
                }
            }
            placed.add(d);
        }
        placed.addAll(waiting);
    }

    /** Whether the span event is the message's carrier, above it or below it in the trace's tree. */
    private static boolean inLine(Derived span, Derived message) {
        return message.ancestorIds().contains(span.event().spanId())
                || span.ancestorIds().contains(message.event().spanId());
    }

    /**
     * The tree facts of a span, from the spans of its trace that are loaded.
     *
     * @param ancestorIds the loaded spans above the span, root first
     * @param rooted the walk up the parent links reached a span with no parent
     * @param insideWork a span of a kind outside {@link #DIALOGUE_PATH_KINDS} lies between the span and its nearest
     *     agent, or the root when it has no agent
     */
    private record Facts(
            @Nullable String parentKind,
            List<String> ancestorKinds,
            List<String> ancestorIds,
            int depth,
            @Nullable String agentId,
            boolean rooted,
            boolean insideWork) {}

    /** A new user message's key: its text, and the assistant message it answers when this trace wrote that one. */
    private record UserKey(@Nullable String answers, String text) {}

    /** The most times any person-facing {@code llm} call received a text as an assistant message, and which call. */
    private record MostReceived(String spanId, int times) {}

    private static final class TraceDerivation {

        private final List<TimelineSpan> spans;
        private final boolean settled;
        private final Instant last;
        private final Map<String, TimelineSpan> byId = new HashMap<>();
        private final Map<String, Facts> facts = new HashMap<>();
        // Each column is parsed once: an agent loop's inputs hold the whole history, so a parse per use is quadratic.
        private final Map<String, List<DialogueMessage>> inputs = new HashMap<>();
        private final Map<String, List<DialogueMessage>> outputs = new HashMap<>();
        private final Map<String, Map<String, Integer>> received = new HashMap<>();
        private final Map<String, Optional<String>> newestUserText = new HashMap<>();
        private final List<Derived> out = new ArrayList<>();

        TraceDerivation(List<TimelineSpan> spans, boolean settled) {
            this.spans = spans;
            this.settled = settled;
            Instant latest = Instant.MIN;
            for (TimelineSpan span : spans) {
                byId.put(span.spanId(), span);
                Instant ended = span.endedAt();
                Instant at = ended == null ? span.startedAt() : ended;
                if (at.isAfter(latest)) latest = at;
            }
            this.last = latest;
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
                                LLM.equals(eventKind(span))
                                        ? llmData(span, input(span), output(span))
                                        : spanData(span)),
                        null,
                        SPAN_ORDINAL,
                        last,
                        f.ancestorIds()));
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
         * The new user messages of each person-facing span, in start order. A new message is keyed by its text and by
         * the assistant message just before it, when a person-facing {@code llm} call of this trace that ended by the
         * span's start wrote that message; else by the start of the turn. A key a span carries no more times than
         * earlier spans of the trace did is a resend, so it is not a new event; {@code n} counts before that check.
         * So the person can say the same words again after a reply of the same turn, and a span that carries an
         * earlier turn's history does not count the message again.
         *
         * <p>When a top agent span received a user message, the agent input gives the messages that opened the turn,
         * as its output is the authority for the replies, and an {@code llm} call under it gives only what the person
         * added while the agent ran ({@link #addedWhileRunning}), placed at that call's start. Any other carrier's
         * messages are placed where the turn received them ({@link #receivedAt}).
         */
        private void userMessages(List<TimelineSpan> facing) {
            Map<UserKey, Integer> emitted = new HashMap<>();
            for (int i = 0; i < facing.size(); i++) {
                TimelineSpan carrier = facing.get(i);
                String opening = agentsNewestUserText(carrier);
                List<UserKey> users = opening == null
                        ? newUserMessages(carrier, facing)
                        : addedWhileRunning(carrier, opening, facing);
                Instant at = opening == null ? receivedAt(carrier, facing.subList(0, i)) : carrier.startedAt();
                Map<UserKey, Integer> inSpan = new HashMap<>();
                for (int n = 0; n < users.size(); n++) {
                    UserKey key = users.get(n);
                    int occurrence = inSpan.merge(key, 1, Integer::sum);
                    if (occurrence <= emitted.getOrDefault(key, 0)) continue;
                    ObjectNode data =
                            MAPPER.createObjectNode().put("text", key.text()).put("from_person", true);
                    out.add(new Derived(
                            messageEvent(carrier, spanEventId(carrier) + "#user:" + n, Event.USER_MESSAGE, at, data),
                            null,
                            n,
                            last,
                            lineage(carrier)));
                }
                inSpan.forEach((key, count) -> emitted.merge(key, count, Math::max));
            }
        }

        /**
         * Where the messages a carrier received are placed: at the start of the highest loaded span above it that holds
         * no earlier person-facing span of the trace, since that span received them before any work it holds, else at
         * the carrier's start. So a retrieval or a guardrail the turn runs before its first model call comes after the
         * message, while a message of a later turn or call of the same trace stays at its own carrier.
         */
        private Instant receivedAt(TimelineSpan carrier, List<TimelineSpan> earlier) {
            List<String> lineage = facts(carrier).ancestorIds();
            return lineage.stream()
                    .filter(ancestorId -> !holdsAny(ancestorId, earlier, lineage))
                    .findFirst()
                    .map(ancestorId ->
                            Objects.requireNonNull(byId.get(ancestorId)).startedAt())
                    .filter(start -> start.isBefore(carrier.startedAt()))
                    .orElse(carrier.startedAt());
        }

        /** Whether a span outside {@code lineage} lies below the given span. */
        private boolean holdsAny(String spanId, List<TimelineSpan> spans, List<String> lineage) {
            for (TimelineSpan span : spans) {
                if (!lineage.contains(span.spanId())
                        && facts(span).ancestorIds().contains(spanId)) return true;
            }
            return false;
        }

        /** The user messages after the last assistant message of the span's input, keyed as {@link #userMessages} says. */
        private List<UserKey> newUserMessages(TimelineSpan carrier, List<TimelineSpan> facing) {
            List<DialogueMessage> input = personInput(carrier);
            List<DialogueMessage> fresh = DialogueMessages.afterLastAssistant(input);
            String answers = input.size() == fresh.size()
                    ? null
                    : answeredInTrace(input.get(input.size() - fresh.size() - 1), carrier, facing);
            return fresh.stream()
                    .filter(this::fromUser)
                    .map(m -> new UserKey(answers, m.text()))
                    .toList();
        }

        /**
         * The user messages an {@code llm} call under a top agent holds after the agent's newest user message, each
         * keyed by the assistant message before it: what the person added while the agent ran. A call whose input does
         * not hold that message is not on the person's thread, such as a memory summary, whose instruction code wrote
         * under the user role, so it gives none; an instruction code adds after the person's thread reads as the
         * person's.
         */
        private List<UserKey> addedWhileRunning(TimelineSpan carrier, String opening, List<TimelineSpan> facing) {
            List<DialogueMessage> input = input(carrier);
            int from = openingCopy(input, carrier, opening, facing);
            List<UserKey> added = new ArrayList<>();
            String answers = null;
            for (int i = 0; i < input.size(); i++) {
                DialogueMessage m = input.get(i);
                if (ASSISTANT.equals(m.role()) && m.dialogue()) {
                    answers = messageKey(m);
                } else if (from >= 0 && i > from && fromUser(m)) {
                    added.add(new UserKey(answers, m.text()));
                }
            }
            return added;
        }

        /**
         * Where the agent's newest user message is in the call's input, or -1: its last copy, or an earlier copy when
         * only assistant messages this trace wrote before the call lie between them, since the person then said the
         * same words again while the agent ran. A copy after history the trace did not write is the opening itself.
         */
        private int openingCopy(
                List<DialogueMessage> input, TimelineSpan carrier, String opening, List<TimelineSpan> facing) {
            int first = -1;
            int copy = -1;
            for (int i = 0; i < input.size(); i++) {
                if (!fromUser(input.get(i)) || !opening.equals(input.get(i).text())) continue;
                if (first < 0) first = i;
                copy = i;
            }
            boolean writtenBetween = false;
            for (int i = copy - 1; i >= first; i--) {
                DialogueMessage m = input.get(i);
                if (ASSISTANT.equals(m.role()) && m.dialogue()) {
                    if (answeredInTrace(m, carrier, facing) == null) break;
                    writtenBetween = true;
                } else if (writtenBetween && fromUser(m) && opening.equals(m.text())) {
                    copy = i;
                    writtenBetween = false;
                }
            }
            return copy;
        }

        private boolean fromUser(DialogueMessage m) {
            return USER.equals(m.role()) && m.dialogue();
        }

        /** The text of the newest user message of the top agent above an {@code llm} span, when that agent received one. */
        private @Nullable String agentsNewestUserText(TimelineSpan span) {
            String agentId = facts(span).agentId();
            TimelineSpan agent = agentId == null ? null : byId.get(agentId);
            if (!LLM.equals(eventKind(span)) || agent == null) return null;
            return newestUserText
                    .computeIfAbsent(
                            agent.spanId(),
                            k -> personInput(agent).stream()
                                    .filter(this::fromUser)
                                    .reduce((first, second) -> second)
                                    .map(DialogueMessage::text))
                    .orElse(null);
        }

        /** The key of an assistant message when a person-facing {@code llm} call that ended by then wrote it. */
        private @Nullable String answeredInTrace(
                DialogueMessage answered, TimelineSpan carrier, List<TimelineSpan> facing) {
            String key = messageKey(answered);
            for (TimelineSpan writer : facing) {
                Instant ended = writer.endedAt();
                if (writer.spanId().equals(carrier.spanId())
                        || ended == null
                        || ended.isAfter(carrier.startedAt())
                        || !LLM.equals(eventKind(writer))) {
                    continue;
                }
                for (DialogueMessage m : output(writer)) {
                    if (ASSISTANT.equals(m.role()) && key.equals(messageKey(m))) return key;
                }
            }
            return null;
        }

        /**
         * The replies of the turn: the assistant texts each output adds after its last user message, so an output that
         * holds the whole chat gives only this turn's. When a top agent span returned text, the agent output is the
         * authority: an {@code llm} text is a reply when the agent returned it, or when another person-facing {@code
         * llm} call received it as an assistant message more times than the writer's own input holds it (the agent
         * loop carried the new text on, not only the old history); an agent text no {@code llm} under it showed is a
         * reply of the agent span. Without agent text, every text of a person-facing {@code llm} span is a reply.
         *
         * <p>Each copy a writer shows is a reply when the writer's input holds every copy this trace showed before:
         * the loop wrote the text again. A copy the writer never received is the same message written twice, as a
         * wrapping {@code llm} span and the call inside it both give. No clock decides a reply, because the next call
         * can start in the instant the last one ended.
         */
        private void replies(List<TimelineSpan> facing) {
            Comparator<TimelineSpan> endOrder = Comparator.comparing(
                            (TimelineSpan s) -> Objects.requireNonNull(s.endedAt()))
                    .thenComparing(s -> facts(s).depth(), Comparator.reverseOrder())
                    .thenComparing(TimelineSpan::spanId);
            List<TimelineSpan> topAgents = facing.stream()
                    .filter(s -> s.endedAt() != null && isTopAgent(s))
                    .sorted(endOrder)
                    .toList();
            Set<String> agentTexts = new HashSet<>();
            for (TimelineSpan agent : topAgents) {
                agentTexts.addAll(newTexts(personOutput(agent)));
            }
            List<TimelineSpan> facingLlms =
                    facing.stream().filter(s -> LLM.equals(eventKind(s))).toList();
            Map<String, MostReceived> most = mostReceived(facingLlms);
            Map<String, Integer> shownCopies = new HashMap<>();
            Map<String, Map<String, Integer>> shownUnderAgent = new HashMap<>();
            for (TimelineSpan writer : facingLlms.stream()
                    .filter(s -> s.endedAt() != null)
                    .sorted(endOrder)
                    .toList()) {
                List<String> texts = newTexts(output(writer));
                Map<String, Integer> shownHere = new HashMap<>();
                for (int n = 0; n < texts.size(); n++) {
                    String text = texts.get(n);
                    int held = timesReceived(writer, text);
                    boolean shown = agentTexts.isEmpty()
                            || agentTexts.contains(text)
                            || carriedOn(most.get(text), writer, held);
                    if (!shown || shownCopies.getOrDefault(text, 0) > held) continue;
                    out.add(reply(writer, n, text));
                    shownHere.merge(text, 1, Integer::sum);
                }
                String agentId = Objects.requireNonNullElse(facts(writer).agentId(), "");
                shownHere.forEach((text, count) -> {
                    shownCopies.merge(text, count, Integer::sum);
                    shownUnderAgent
                            .computeIfAbsent(agentId, k -> new HashMap<>())
                            .merge(text, count, Integer::sum);
                });
            }
            for (TimelineSpan agent : topAgents) {
                Map<String, Integer> shownBelow = shownUnderAgent.getOrDefault(agent.spanId(), Map.of());
                Map<String, Integer> copies = new HashMap<>();
                List<String> texts = newTexts(personOutput(agent));
                for (int n = 0; n < texts.size(); n++) {
                    String text = texts.get(n);
                    if (copies.merge(text, 1, Integer::sum) > shownBelow.getOrDefault(text, 0)) {
                        out.add(reply(agent, n, text));
                    }
                }
            }
        }

        /** Whether a call other than the writer received the text more times than the writer's own input holds it. */
        private static boolean carriedOn(@Nullable MostReceived most, TimelineSpan writer, int held) {
            return most != null && !most.spanId().equals(writer.spanId()) && most.times() > held;
        }

        private Map<String, MostReceived> mostReceived(List<TimelineSpan> llms) {
            Map<String, MostReceived> most = new HashMap<>();
            for (TimelineSpan llm : llms) {
                receivedTexts(llm)
                        .forEach((text, times) -> most.merge(
                                text, new MostReceived(llm.spanId(), times), (a, b) -> b.times() > a.times() ? b : a));
            }
            return most;
        }

        private int timesReceived(TimelineSpan span, String text) {
            return receivedTexts(span).getOrDefault(text, 0);
        }

        /** How many times the span's input holds each assistant text. */
        private Map<String, Integer> receivedTexts(TimelineSpan span) {
            return received.computeIfAbsent(span.spanId(), k -> {
                Map<String, Integer> counts = new HashMap<>();
                for (DialogueMessage m : input(span)) {
                    if (ASSISTANT.equals(m.role()) && m.hasText()) counts.merge(m.text(), 1, Integer::sum);
                }
                return counts;
            });
        }

        private Derived reply(TimelineSpan writer, int n, String text) {
            ObjectNode data = MAPPER.createObjectNode().put("text", text);
            Instant ended = Objects.requireNonNull(writer.endedAt());
            return new Derived(
                    messageEvent(writer, spanEventId(writer) + "#reply:" + n, Event.ASSISTANT_REPLY, ended, data),
                    writer.spanId(),
                    n,
                    last,
                    lineage(writer));
        }

        /** The ancestors of a message the span carries: the span's, then the span. */
        private List<String> lineage(TimelineSpan carrier) {
            List<String> ids = new ArrayList<>(facts(carrier).ancestorIds());
            ids.add(carrier.spanId());
            return ids;
        }

        private Event messageEvent(TimelineSpan carrier, String id, String kind, Instant at, ObjectNode data) {
            Facts f = facts(carrier);
            String carrierKind = eventKind(carrier);
            List<String> ancestors = new ArrayList<>(f.ancestorKinds());
            ancestors.add(carrierKind);
            String agentId = AGENT.equals(carrierKind) ? carrier.spanId() : f.agentId();
            return event(id, kind, at, carrier, carrier.spanId(), carrierKind, ancestors, f.depth() + 1, agentId, data);
        }

        /** The span's input messages, parsed once. */
        private List<DialogueMessage> input(TimelineSpan span) {
            return inputs.computeIfAbsent(span.spanId(), k -> DialogueMessages.read(span.input(), USER));
        }

        /** The span's output messages, parsed once. */
        private List<DialogueMessage> output(TimelineSpan span) {
            return outputs.computeIfAbsent(span.spanId(), k -> DialogueMessages.read(span.output(), ASSISTANT));
        }

        /**
         * What a person-facing span received: an {@code llm} call's input, or a top agent's when it is a message list or
         * text, since an agent span may hold a framework's call arguments instead.
         */
        private List<DialogueMessage> personInput(TimelineSpan span) {
            return LLM.equals(eventKind(span)) ? input(span) : DialogueMessages.readDialogue(span.input(), USER);
        }

        /** What a top agent returned, when it is a message list or text, not a framework's return object. */
        private List<DialogueMessage> personOutput(TimelineSpan agent) {
            return DialogueMessages.readDialogue(agent.output(), ASSISTANT);
        }

        /** Whether the span has a known place in the trace's tree: its parent links reach a root, or the trace is settled. */
        private boolean placed(TimelineSpan span) {
            return settled || facts(span).rooted();
        }

        /**
         * An {@code agent} span with no {@code agent} ancestor and no work span above it: the agent the person talks
         * to. An agent a tool runs answers the model that called the tool, not the person.
         */
        private boolean isTopAgent(TimelineSpan span) {
            Facts f = facts(span);
            return AGENT.equals(eventKind(span)) && f.agentId() == null && !f.insideWork() && placed(span);
        }

        /**
         * A top agent span, or an {@code llm} span whose nearest agent is a top agent or that has no agent, with no
         * work span, such as a tool or a guardrail, between them: a model call a tool makes is code talking to a
         * model.
         */
        private boolean isPersonFacing(TimelineSpan span) {
            if (isTopAgent(span)) return true;
            if (!LLM.equals(eventKind(span)) || !placed(span) || facts(span).insideWork()) return false;
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
            boolean rooted = parentId == null;
            while (parentId != null && visited.add(parentId)) {
                TimelineSpan parent = byId.get(parentId);
                if (parent == null) break;
                up.add(parent);
                parentId = parent.parentSpanId();
                rooted = parentId == null;
            }
            Collections.reverse(up);
            List<String> kinds =
                    up.stream().map(SessionTimelineBuilder::eventKind).toList();
            String agentId = null;
            boolean insideWork = false;
            for (TimelineSpan ancestor : up) {
                String kind = eventKind(ancestor);
                if (AGENT.equals(kind)) {
                    agentId = ancestor.spanId();
                    insideWork = false;
                } else if (!DIALOGUE_PATH_KINDS.contains(kind)) {
                    insideWork = true;
                }
            }
            return new Facts(
                    kinds.isEmpty() ? null : kinds.get(kinds.size() - 1),
                    kinds,
                    up.stream().map(TimelineSpan::spanId).toList(),
                    up.size(),
                    agentId,
                    rooted,
                    insideWork);
        }
    }

    /** The texts of the assistant messages an output adds after its last user message, that carry real text. */
    private static List<String> newTexts(List<DialogueMessage> output) {
        return DialogueMessages.afterLastUser(output).stream()
                .filter(m -> ASSISTANT.equals(m.role()) && m.hasText())
                .map(DialogueMessage::text)
                .toList();
    }

    /** What tells two assistant messages apart: the text, or for a message with only tool calls, their ids. */
    private static String messageKey(DialogueMessage m) {
        if (m.hasText()) return m.text();
        StringJoiner ids = new StringJoiner(",", "tool_calls:", "");
        for (JsonNode call : m.toolCalls()) {
            ids.add(call.path("id").asText(call.path("call_id").asText("")));
        }
        return ids.toString();
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
        Event unsized = new Event(
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
                0);
        return unsized.withBytes(measure(unsized));
    }

    /**
     * The one place an event's size is computed: the UTF-8 length of the event's own JSON form without {@code
     * position} and {@code bytes}, so cutting a tail, which renumbers positions, never changes a size.
     */
    private static long measure(Event event) {
        ByteCount count = new ByteCount();
        try {
            SIZER.writeValue(count, event);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count.bytes;
    }

    /** Counts the bytes written to it and keeps none. */
    private static final class ByteCount extends OutputStream {
        private long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }

    /**
     * The data of a span event of any kind but {@code llm}. A tool's input and output are its {@code tool_call}
     * arguments and result, else the span's payload, which holds a text result the row does not.
     */
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
                data.set("input", parse(orElse(tool == null ? null : tool.arguments(), span.input())));
                data.set("output", parse(orElse(tool == null ? null : tool.result(), span.output())));
            }
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

    private static @Nullable String orElse(@Nullable String value, @Nullable String fallback) {
        return value == null ? fallback : value;
    }

    private static ObjectNode llmData(TimelineSpan span, List<DialogueMessage> input, List<DialogueMessage> output) {
        ObjectNode data = MAPPER.createObjectNode();
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
        List<DialogueMessage> replies = DialogueMessages.afterLastUser(output).stream()
                .filter(m -> ASSISTANT.equals(m.role()))
                .toList();
        ObjectNode result = data.putObject("output");
        result.put("text", joined(replies.stream().map(DialogueMessage::text).toList()));
        ArrayNode toolCalls = result.putArray("tool_calls");
        replies.forEach(m -> m.toolCalls().forEach(toolCalls::add));
        result.put(
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
        return data;
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

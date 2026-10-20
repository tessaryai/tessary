// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.prompt.PromptCraft;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs one task that ends in a single JSON answer over a launcher session: it starts the session, sends the task, words
 * each tool call into a step, reads the answer from the reply, corrects the agent at most once, and stops at the
 * deadline. Every path stops the session and reads it to its close, so the session client books the last reply. The
 * caller owns the MCP keys and revokes them after {@link #run} returns.
 */
@Component
public class AgentSessionRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionRunner.class);

    private static final String AGENT = "agent";

    /** The prompt texts the one-shot lanes sent, byte for byte. */
    static final String SCHEMA_INSTRUCTION =
            PromptCraft.text(AGENT, "schema_instruction.md").stripTrailing();

    static final String CORRECTION_NO_TOOL_BUDGET =
            PromptCraft.text(AGENT, "correction_no_tool_budget.md").stripTrailing();

    static final String CORRECTION_MISSING_KEYS =
            PromptCraft.text(AGENT, "correction_missing_keys.md").stripTrailing();

    static final String CORRECTION_UNPARSEABLE =
            PromptCraft.text(AGENT, "correction_unparseable.md").stripTrailing();

    /** A session waits this long for the next message; the runner sends one at most per reply. */
    static final long IDLE_TIMEOUT_MS = 120_000;

    /** How long the stop may take to reach {@code closed}; the run's lease leaves room for it past the deadline. */
    static final Duration DRAIN = Duration.ofSeconds(60);

    /** Streams in a row that read nothing before the run gives up on the session. */
    static final int RECONNECTS = 4;

    private static final Set<String> PROVIDER_KINDS =
            Set.of("provider_rejected", "request_refused", "provider_unavailable");

    private final AgentSessionClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Deadlines deadlines;

    @Autowired
    public AgentSessionRunner(AgentSessionClient client, ObjectMapper mapper) {
        this(client, mapper, Clock.systemUTC(), Deadlines.VIRTUAL);
    }

    public AgentSessionRunner(AgentSessionClient client, ObjectMapper mapper, Clock clock, Deadlines deadlines) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
        this.deadlines = deadlines;
    }

    /**
     * @param systemPrompt null runs the agent's default prompt (RCA) @param repo the repository the sandbox clones
     * first, RCA only @param jsonSchema the answer's schema; its top-level {@code required} keys are checked @param
     * deadline from the moment {@link #run} is entered, start included @param maxToolCalls the agent's step cap per
     * reply
     */
    public record Task(
            String projectId,
            ModelLane lane,
            LlmUsageAccountant.Subject subject,
            @Nullable String systemPrompt,
            Map<String, String> files,
            List<AgentSessionClient.McpServer> mcp,
            AgentSessionClient.@Nullable Clone repo,
            String prompt,
            String jsonSchema,
            Duration deadline,
            int maxToolCalls,
            StepWords.Context words) {}

    /** Called on the runner's thread; a sink that throws is logged and the run goes on. */
    public interface StepSink {
        void session(String sessionId);

        /** @param atMs since {@link #run} was entered */
        void started(String callId, StepWords.Words w, long atMs);

        void ended(String callId, long atMs, boolean ok, StepWords.Words w);
    }

    /**
     * @param json the answer object, compact @param usage every reply's usage summed, for the span; booking is the
     * session client's @param steps the tool calls that got a step
     */
    public record Answer(String json, AgentSpanTelemetry.AgentUsage usage, int steps) {}

    /** Timers and pauses, so a test can fire the deadline without waiting for it. */
    public interface Deadlines {

        /** Runs {@code action} once after {@code delay}, on another thread, unless cancelled first. */
        Timer after(Duration delay, Runnable action);

        void pause(Duration delay) throws InterruptedException;

        interface Timer {
            void cancel();
        }

        Deadlines VIRTUAL = new Deadlines() {
            @Override
            public Timer after(Duration delay, Runnable action) {
                AtomicBoolean live = new AtomicBoolean(true);
                Thread timer = Thread.ofVirtual().name("agent-run-deadline").start(() -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (live.compareAndSet(true, false)) action.run();
                });
                return () -> {
                    if (live.compareAndSet(true, false)) timer.interrupt();
                };
            }

            @Override
            public void pause(Duration delay) throws InterruptedException {
                Thread.sleep(delay);
            }
        };
    }

    /**
     * @throws TessaryException {@link ProviderStop} when the model provider stopped a reply; {@code AgentRunError}
     * {@code TIMED_OUT} at the deadline, {@code BAD_OUTPUT} when the corrected reply still has no answer,
     * {@code RUN_FAILED} when the session ends or its events stop, and whatever {@link AgentSessionClient#start}
     * throws
     */
    public Answer run(Task task, StepSink sink) {
        Watch watch = new Watch();
        Run run = new Run(task, sink, watch);
        Deadlines.Timer deadline = deadlines.after(task.deadline(), () -> watch.deadline(task.projectId()));
        String outcome = "failed";
        try {
            Answer answer = run.answer();
            outcome = "answered";
            return answer;
        } catch (TessaryException e) {
            outcome = e.error().code();
            throw e;
        } finally {
            deadline.cancel();
            run.finish();
            StructuredLog.info(log, Markers.OPS, "agentrun.session.ended")
                    .message(
                            "agent session run on lane %s for %s %s ended (%s) after %d steps and %d replies",
                            task.lane().wire(),
                            task.subject().kind(),
                            task.subject().id(),
                            outcome,
                            run.steps,
                            run.replies)
                    .field("project", task.projectId())
                    .field("lane", task.lane().wire())
                    .field("subjectKind", task.subject().kind())
                    .field("subjectId", task.subject().id())
                    .field("session", run.sessionId)
                    .field("outcome", outcome)
                    .field("steps", run.steps)
                    .field("replies", run.replies)
                    .field("corrected", run.corrected)
                    .field("durationMs", run.elapsedMs())
                    .log();
        }
    }

    /** The part of a run the deadline timer touches, from its own thread. */
    private final class Watch {
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicReference<@Nullable Stream<SessionEvent>> current = new AtomicReference<>();
        private final AtomicReference<@Nullable String> sessionId = new AtomicReference<>();
        private final AtomicReference<Deadlines.@Nullable Timer> afterDeadline = new AtomicReference<>();
        private final AtomicBoolean expired = new AtomicBoolean();
        private volatile @Nullable Instant timedOutAt;

        void started(String id) {
            sessionId.set(id);
        }

        void deadline(String projectId) {
            timedOutAt = clock.instant();
            timedOut.set(true);
            String id = sessionId.get();
            if (id == null) return;
            quiet("abort", projectId, id, () -> client.abort(id));
            quiet("stop", projectId, id, () -> client.stop(id));
            afterDeadline.set(deadlines.after(DRAIN, this::closeCurrent));
        }

        private void quiet(String op, String projectId, String id, Runnable call) {
            try {
                call.run();
            } catch (RuntimeException e) {
                StructuredLog.warn(log, Markers.OPS, "agentrun.session." + op + "-failed")
                        .message("could not %s session %s at the deadline", op, id)
                        .field("project", projectId)
                        .field("session", id)
                        .field(
                                "error",
                                e instanceof TessaryException t
                                        ? t.error().code()
                                        : e.getClass().getSimpleName())
                        .log();
            }
        }

        void closeCurrent() {
            Stream<SessionEvent> stream = current.get();
            if (stream != null) stream.close();
        }

        /** The drain's time is up. */
        void expire() {
            expired.set(true);
            closeCurrent();
        }

        void cancel() {
            Deadlines.Timer timer = afterDeadline.getAndSet(null);
            if (timer != null) timer.cancel();
        }
    }

    /** One run's state, read and written on the runner's thread only. */
    private final class Run {
        private final Task task;
        private final StepSink sink;
        private final Instant began = clock.instant();
        private final StepWords.RowCursor cursor = new StepWords.RowCursor();
        private final Map<String, StepWords.Words> open = new HashMap<>();
        private final JsonNode schema;
        private final String instruction;

        private final Watch watch;
        private @Nullable String sessionId;

        private long lastSeq;
        private boolean closedSeen;
        int steps;
        int replies;
        boolean corrected;
        private int toolCalls;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private @Nullable BigDecimal costUsd;

        private final StringBuilder text = new StringBuilder();
        private SessionEvent.@Nullable EndReason endReason;
        private SessionEvent.@Nullable Failure providerFailure;
        private @Nullable String failureKind;

        Run(Task task, StepSink sink, Watch watch) {
            this.task = task;
            this.sink = sink;
            this.watch = watch;
            try {
                this.schema = mapper.readTree(task.jsonSchema());
            } catch (JsonProcessingException e) {
                throw new TessaryException(CommonError.VALIDATION_FAILED, e, "the task's JSON schema does not parse");
            }
            this.instruction = "\n\n" + SCHEMA_INSTRUCTION.replace("{schema}", schema.toString());
        }

        long elapsedMs() {
            return Duration.between(began, clock.instant()).toMillis();
        }

        Answer answer() {
            String id = client.start(new AgentSessionClient.Start(
                            task.projectId(),
                            task.lane(),
                            task.systemPrompt(),
                            task.files(),
                            task.mcp(),
                            IDLE_TIMEOUT_MS,
                            task.maxToolCalls(),
                            task.subject(),
                            task.repo()))
                    .id();
            sessionId = id;
            watch.started(id);
            if (watch.timedOut.get()) throw timedOut();
            try {
                sink.session(id);
            } catch (RuntimeException e) {
                unrecorded(e);
            }
            send(id, task.prompt() + instruction);
            while (true) {
                readReply(id);
                SessionEvent.Failure provider = providerFailure;
                if (provider != null) throw new ProviderStop(provider.kind(), provider.detail());
                Optional<ObjectNode> parsed = JsonAnswer.extract(mapper, text.toString());
                List<String> missing = JsonAnswer.missingKeys(parsed.orElse(null), schema);
                if (parsed.isPresent() && missing.isEmpty()) {
                    return new Answer(parsed.get().toString(), usage(), steps);
                }
                if (corrected) {
                    throw new TessaryException(
                            AgentRunError.BAD_OUTPUT,
                            parsed.isPresent()
                                    ? "the corrected answer is still missing " + String.join(", ", missing)
                                    : "the corrected reply holds no JSON object");
                }
                corrected = true;
                send(id, correction(parsed.isPresent(), missing));
            }
        }

        /** The one correction, chosen as the one-shot lanes chose it. */
        private String correction(boolean parsed, List<String> missing) {
            if (parsed) return CORRECTION_MISSING_KEYS.replace("{missing}", String.join(", ", missing)) + instruction;
            boolean empty = text.toString().isBlank();
            if (empty || endReason == SessionEvent.EndReason.LIMIT) {
                return toolCalls == 0 ? task.prompt() + instruction : CORRECTION_NO_TOOL_BUDGET + instruction;
            }
            return CORRECTION_UNPARSEABLE + instruction;
        }

        private void send(String id, String message) {
            text.setLength(0);
            endReason = null;
            providerFailure = null;
            failureKind = null;
            AgentSessionClient.SendOutcome outcome = client.send(id, message);
            if (outcome != AgentSessionClient.SendOutcome.ACCEPTED) {
                if (watch.timedOut.get()) throw timedOut();
                throw new TessaryException(AgentRunError.RUN_FAILED, "the session took no message (" + outcome + ")");
            }
        }

        /** Reads until the reply's {@code message_end}, resuming a stream that ends early. */
        private void readReply(String id) {
            int failures = 0;
            while (true) {
                boolean readAny = false;
                try (Stream<SessionEvent> events = client.events(id, lastSeq)) {
                    watch.current.set(events);
                    Iterator<SessionEvent> it = events.iterator();
                    while (it.hasNext()) {
                        SessionEvent event = it.next();
                        if (!(event instanceof SessionEvent.Closed) && event.seq() <= lastSeq) continue;
                        readAny = true;
                        failures = 0;
                        if (handle(event)) return;
                    }
                } catch (TessaryException e) {
                    if (!reconnectable(e) || failures >= RECONNECTS) throw e;
                } finally {
                    watch.current.set(null);
                }
                if (watch.timedOut.get()) throw timedOut();
                if (readAny) continue;
                if (failures >= RECONNECTS) {
                    throw new TessaryException(AgentRunError.RUN_FAILED, "the session's events stopped");
                }
                Duration wait = Duration.ofSeconds(1L << failures);
                failures++;
                try {
                    deadlines.pause(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TessaryException(CommonError.INTERRUPTED, e, "agent session events");
                }
            }
        }

        private static boolean reconnectable(TessaryException e) {
            return e.error() == AgentRunError.LAUNCHER_UNAVAILABLE || e.error() == AgentRunError.TIMED_OUT;
        }

        /** True when the reply ended; throws when the session closed under it. */
        private boolean handle(SessionEvent event) {
            lastSeq = Math.max(lastSeq, event.seq());
            switch (event) {
                case SessionEvent.Text t -> text.append(t.delta());
                case SessionEvent.ToolStart start -> {
                    text.setLength(0);
                    toolCalls++;
                    StepWords.Words w = StepWords.of(start, task.words(), cursor);
                    if (w != null) {
                        steps++;
                        open.put(start.callId(), w);
                        long at = elapsedMs();
                        try {
                            sink.started(start.callId(), w, at);
                        } catch (RuntimeException e) {
                            unrecorded(e);
                        }
                    }
                }
                case SessionEvent.ToolEnd end -> {
                    text.setLength(0);
                    cursor.ended(end.callId(), end.ok());
                    StepWords.Words w = open.remove(end.callId());
                    if (w != null) {
                        long at = elapsedMs();
                        try {
                            sink.ended(end.callId(), at, end.ok(), w);
                        } catch (RuntimeException e) {
                            unrecorded(e);
                        }
                    }
                }
                case SessionEvent.Failure f -> {
                    failureKind = f.kind();
                    if (PROVIDER_KINDS.contains(f.kind())) providerFailure = f;
                }
                case SessionEvent.MessageEnd end -> {
                    replies++;
                    endReason = end.reason();
                    add(end.usage());
                    if (watch.timedOut.get()) throw timedOut();
                    return true;
                }
                case SessionEvent.Closed c -> {
                    closedSeen = true;
                    if (watch.timedOut.get()) throw timedOut();
                    throw new TessaryException(
                            AgentRunError.RUN_FAILED,
                            "the session closed (" + c.reason().name().toLowerCase(java.util.Locale.ROOT)
                                    + ") before its reply ended"
                                    + (failureKind == null ? "" : " (" + failureKind + ")"));
                }
            }
            return false;
        }

        private void add(AgentSpanTelemetry.AgentUsage u) {
            inputTokens += u.inputTokens();
            outputTokens += u.outputTokens();
            cacheReadTokens += u.cacheReadTokens();
            cacheWriteTokens += u.cacheWriteTokens();
            BigDecimal cost = u.costUsd();
            if (cost != null) costUsd = costUsd == null ? cost : costUsd.add(cost);
        }

        private AgentSpanTelemetry.AgentUsage usage() {
            return new AgentSpanTelemetry.AgentUsage(
                    inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, costUsd);
        }

        private TessaryException timedOut() {
            return new TessaryException(AgentRunError.TIMED_OUT, task.deadline().toMillis());
        }

        /**
         * Stops the session and reads it to {@code closed}, within {@link #DRAIN} of the stop, or of the deadline when
         * the run timed out.
         */
        void finish() {
            watch.cancel();
            String id = sessionId;
            if (id == null || closedSeen) return;
            try {
                client.stop(id);
            } catch (RuntimeException e) {
                StructuredLog.warn(log, Markers.OPS, "agentrun.session.stop-failed")
                        .message("could not stop session %s", id)
                        .field("session", id)
                        .field(
                                "error",
                                e instanceof TessaryException t
                                        ? t.error().code()
                                        : e.getClass().getSimpleName())
                        .log();
            }
            Instant from = watch.timedOutAt;
            Duration left = from == null ? DRAIN : DRAIN.minus(Duration.between(from, clock.instant()));
            Deadlines.Timer drain = deadlines.after(left.isNegative() ? Duration.ZERO : left, watch::expire);
            try {
                for (int tries = 0; tries < RECONNECTS && !closedSeen && !watch.expired.get(); tries++) {
                    drainOnce(id);
                }
            } catch (TessaryException e) {
                StructuredLog.warn(log, Markers.OPS, "agentrun.session.drain-failed")
                        .message(
                                "could not read session %s to its close (%s)",
                                id, e.error().code())
                        .field("session", id)
                        .field("code", e.error().code())
                        .log();
            } finally {
                drain.cancel();
            }
        }

        private void drainOnce(String id) {
            try (Stream<SessionEvent> events = client.events(id, lastSeq)) {
                watch.current.set(events);
                if (watch.expired.get()) return;
                Iterator<SessionEvent> it = events.iterator();
                while (!closedSeen && it.hasNext()) {
                    SessionEvent event = it.next();
                    if (event instanceof SessionEvent.Closed) closedSeen = true;
                    else lastSeq = Math.max(lastSeq, event.seq());
                }
            } finally {
                watch.current.set(null);
            }
        }

        private void unrecorded(RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "agentrun.session.step-unrecorded")
                    .message("could not record a step of session %s", String.valueOf(sessionId))
                    .field("session", sessionId)
                    .field("subjectId", task.subject().id())
                    .cause(e)
                    .log();
        }
    }
}

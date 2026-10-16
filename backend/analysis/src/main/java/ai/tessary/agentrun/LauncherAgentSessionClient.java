// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The launcher-backed {@link AgentSessionClient}. The credential, launcher key and MCP tokens never reach a log line or
 * exception message. A 404 not naming an unknown session maps to {@code LAUNCHER_MISCONFIGURED}.
 */
@Component
public class LauncherAgentSessionClient implements AgentSessionClient {

    private static final Logger log = LoggerFactory.getLogger(LauncherAgentSessionClient.class);

    private static final String ROUTE = "/sessions";

    /** The launcher's own limits plus 30 s: 90 s for a sandbox to become ready. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(2);

    /** 10 s for the script to take a message or an abort, plus 30 s. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(40);

    /** A 5 s abort grace, a 5 s container stop and the kill, plus 30 s. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    private final AgentRunProperties props;
    private final ObserverProperties observerProps;
    private final AgentModels models;
    private final AgenticCredentialResolver credentials;
    private final LlmUsageAccountant usage;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Clock clock;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Autowired
    public LauncherAgentSessionClient(
            AgentRunProperties props,
            ObserverProperties observerProps,
            AgentModels models,
            AgenticCredentialResolver credentials,
            LlmUsageAccountant usage,
            ObjectMapper mapper) {
        this(props, observerProps, models, credentials, usage, mapper, Clock.systemUTC());
    }

    LauncherAgentSessionClient(
            AgentRunProperties props,
            ObserverProperties observerProps,
            AgentModels models,
            AgenticCredentialResolver credentials,
            LlmUsageAccountant usage,
            ObjectMapper mapper,
            Clock clock) {
        this.props = props;
        this.observerProps = observerProps;
        this.models = models;
        this.credentials = credentials;
        this.usage = usage;
        this.mapper = mapper;
        this.clock = clock;
        // HTTP/1.1 so a GET carries no h2c upgrade and an event stream is the launcher's chunked body.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * A session leaves the map only when its stream yields {@code closed} or the launcher no longer knows it; a 404
     * elsewhere only marks it gone, since a reconnected stream may still hold the last reply's {@code message_end}.
     */
    private static final class SessionState {
        final String projectId;
        final ModelLane lane;
        final String model;
        final ModelProvider provider;
        /** Fixed per session from the start credential. */
        final boolean platformFunded;

        final LlmUsageAccountant.Subject subject;
        final AtomicReference<@Nullable Reply> open = new AtomicReference<>();

        /** The launcher answered 404 for it: no new reply starts. */
        volatile boolean gone;

        /** Read and written only inside {@code synchronized (this)}. */
        long lastBookedSeq;

        SessionState(
                String projectId,
                ModelLane lane,
                String model,
                ModelProvider provider,
                boolean platformFunded,
                LlmUsageAccountant.Subject subject) {
            this.projectId = projectId;
            this.lane = lane;
            this.model = model;
            this.provider = provider;
            this.platformFunded = platformFunded;
            this.subject = subject;
        }

        void releaseOpen() {
            Reply reply = open.getAndSet(null);
            if (reply != null) reply.release();
        }
    }

    /** Released exactly once, whichever ending comes first. */
    private final class Reply {
        final AgenticCredentialResolver.Credential credential;
        final AtomicBoolean released = new AtomicBoolean();

        Reply(AgenticCredentialResolver.Credential credential) {
            this.credential = credential;
        }

        void release() {
            if (released.compareAndSet(false, true)) credentials.release(credential);
        }
    }

    @Override
    public Session start(Start start) {
        String launcherUrl = launcherUrl();
        AgentModels.Choice choice = models.resolve(start.projectId(), start.lane());
        AgenticCredentialResolver.Credential credential = credentials.resolve(start.projectId(), choice.provider());
        Instant began = clock.instant();
        String sessionId;
        try {
            String body = mapper.writeValueAsString(startBody(start, choice, credential));
            HttpRequest request = request(launcherUrl, ROUTE)
                    .timeout(START_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp =
                    exchange("start", request, launcherUrl, start.lane().wire(), START_TIMEOUT);
            if (resp.statusCode() / 100 != 2) {
                throw refusal(
                        "start",
                        resp.statusCode(),
                        resp.body(),
                        launcherUrl,
                        start.lane().wire(),
                        START_TIMEOUT);
            }
            sessionId = mapper.readTree(resp.body()).path("session_id").asText("");
        } catch (JsonProcessingException e) {
            throw new TessaryException(AgentRunError.BAD_OUTPUT, e, "the launcher's session answer did not parse");
        } finally {
            credentials.release(credential);
        }
        if (sessionId.isBlank()) {
            throw new TessaryException(AgentRunError.BAD_OUTPUT, "the launcher started a session without an id");
        }
        sessions.put(
                sessionId,
                new SessionState(
                        start.projectId(),
                        start.lane(),
                        choice.model(),
                        choice.provider(),
                        credential.platformFunded(),
                        start.subject()));
        long fileBytes = start.files().values().stream()
                .mapToLong(LauncherAgentSessionClient::utf8Bytes)
                .sum();
        StructuredLog.info(log, Markers.OPS, "agentsession.started")
                .message(
                        "agent session %s started on lane %s with %d files (%d bytes) and %d MCP servers",
                        sessionId,
                        start.lane().wire(),
                        start.files().size(),
                        fileBytes,
                        start.mcpServers().size())
                .field("project", start.projectId())
                .field("lane", start.lane().wire())
                .field("subjectKind", start.subject().kind())
                .field("subjectId", start.subject().id())
                .field("session", sessionId)
                .field("files", start.files().size())
                .field("fileBytes", fileBytes)
                .field("systemPromptBytes", utf8Bytes(start.systemPrompt()))
                .field("mcpServers", start.mcpServers().size())
                .field("durationMs", Duration.between(began, clock.instant()).toMillis())
                .log();
        return new Session(sessionId);
    }

    @Override
    public SendOutcome send(String sessionId, String text) {
        Instant began = clock.instant();
        SendOutcome outcome = sendOnce(sessionId, text);
        StructuredLog.info(log, Markers.OPS, "agentsession.message")
                .message("session %s message %s", sessionId, outcome.name())
                .field("session", sessionId)
                .field("outcome", outcome.name())
                .field("bytes", utf8Bytes(text))
                .field("durationMs", Duration.between(began, clock.instant()).toMillis())
                .log();
        return outcome;
    }

    private SendOutcome sendOnce(String sessionId, String text) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.gone) return SendOutcome.CLOSED;
        if (state.open.get() != null) return SendOutcome.BUSY;
        String launcherUrl = launcherUrl();
        Reply reply = new Reply(credentials.resolve(state.projectId, state.provider));
        // Open before the POST: the reply's events can reach a reader before the 202 reaches us.
        if (!state.open.compareAndSet(null, reply)) {
            reply.release();
            return SendOutcome.BUSY;
        }
        boolean accepted = false;
        try {
            String body = mapper.createObjectNode().put("text", text).toString();
            HttpRequest request = request(launcherUrl, sessionPath(sessionId) + "/messages")
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = exchange("message", request, launcherUrl, state.lane.wire(), REQUEST_TIMEOUT);
            int status = resp.statusCode();
            if (status / 100 == 2) {
                accepted = true;
                return SendOutcome.ACCEPTED;
            }
            if (status == 409 && "busy".equals(E2bAgentRunSandbox.kindOf(resp.body()))) return SendOutcome.BUSY;
            if (unknownSession(status, resp.body())) {
                gone(state);
                return SendOutcome.CLOSED;
            }
            throw refusal("message", status, resp.body(), launcherUrl, state.lane.wire(), REQUEST_TIMEOUT);
        } finally {
            if (!accepted) {
                state.open.compareAndSet(reply, null);
                reply.release();
            }
        }
    }

    @Override
    public void abort(String sessionId) {
        Instant began = clock.instant();
        String launcherUrl = launcherUrl();
        SessionState state = sessions.get(sessionId);
        String lane = state == null ? null : state.lane.wire();
        HttpRequest request = request(launcherUrl, sessionPath(sessionId) + "/abort")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = exchange("abort", request, launcherUrl, lane, REQUEST_TIMEOUT);
        if (resp.statusCode() / 100 == 2) {
            // The reply stays open until its message_end, so no second reply starts while it winds down.
            Reply reply = state == null ? null : state.open.get();
            if (reply != null) reply.release();
            logAnswered("abort", sessionId, "aborted", began);
            return;
        }
        if (unknownSession(resp.statusCode(), resp.body())) {
            gone(state);
            logAnswered("abort", sessionId, "closed", began);
            return;
        }
        throw refusal("abort", resp.statusCode(), resp.body(), launcherUrl, lane, REQUEST_TIMEOUT);
    }

    @Override
    public void stop(String sessionId) {
        Instant began = clock.instant();
        String launcherUrl = launcherUrl();
        SessionState state = sessions.get(sessionId);
        String lane = state == null ? null : state.lane.wire();
        HttpRequest request = request(launcherUrl, sessionPath(sessionId))
                .timeout(STOP_TIMEOUT)
                .DELETE()
                .build();
        HttpResponse<String> resp = exchange("stop", request, launcherUrl, lane, STOP_TIMEOUT);
        if (resp.statusCode() / 100 == 2) {
            // The state stays until the stream yields closed, so the reply's last message_end is still booked.
            if (state != null) state.releaseOpen();
            logAnswered("stop", sessionId, "stopped", began);
            return;
        }
        if (unknownSession(resp.statusCode(), resp.body())) {
            gone(state);
            logAnswered("stop", sessionId, "closed", began);
            return;
        }
        throw refusal("stop", resp.statusCode(), resp.body(), launcherUrl, lane, STOP_TIMEOUT);
    }

    private void logAnswered(String op, String sessionId, String outcome, Instant began) {
        StructuredLog.info(log, Markers.OPS, "abort".equals(op) ? "agentsession.aborted" : "agentsession.stopped")
                .message("session %s %s: %s", sessionId, op, outcome)
                .field("session", sessionId)
                .field("outcome", outcome)
                .field("durationMs", Duration.between(began, clock.instant()).toMillis())
                .log();
    }

    private static long utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public Stream<SessionEvent> events(String sessionId, long afterSeq) {
        String launcherUrl = launcherUrl();
        SessionState state = sessions.get(sessionId);
        String lane = state == null ? null : state.lane.wire();
        HttpRequest request = request(launcherUrl, sessionPath(sessionId) + "/events?after=" + afterSeq)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(CommonError.INTERRUPTED, e, "agent session events");
        } catch (IOException e) {
            throw unreachable("events", e, launcherUrl, lane, Duration.ZERO);
        }
        if (resp.statusCode() / 100 == 2) {
            EventReader reader = new EventReader(sessionId, state, resp.body());
            return StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(reader, Spliterator.ORDERED | Spliterator.NONNULL),
                            false)
                    .onClose(reader::closeByCaller);
        }
        String body = drain(resp.body());
        if (unknownSession(resp.statusCode(), body)) {
            closed(sessionId, state);
            return Stream.of(new SessionEvent.Closed(afterSeq, SessionEvent.CloseReason.UNKNOWN));
        }
        throw refusal("events", resp.statusCode(), body, launcherUrl, lane, Duration.ZERO);
    }

    private void closed(String sessionId, @Nullable SessionState state) {
        if (state == null) return;
        state.releaseOpen();
        sessions.remove(sessionId, state);
    }

    /** A 404 outside the stream: the open reply is freed and no new one starts, but its stream may still book. */
    private static void gone(@Nullable SessionState state) {
        if (state == null) return;
        state.gone = true;
        state.releaseOpen();
    }

    /**
     * Booking comes first, so a ledger write that throws ends the stream and the reconnect books it again. A replay at
     * or below the last booked sequence does nothing. With no state (after a backend restart) the spend is logged as a
     * warning, never silently lost.
     */
    private void ended(String sessionId, @Nullable SessionState state, SessionEvent.MessageEnd end) {
        AgentSpanTelemetry.AgentUsage u = end.usage();
        if (state == null) {
            StructuredLog.warn(log, Markers.OPS, "agentsession.reply-unbooked")
                    .message(
                            "session %s reply ended (%s) at seq %d with no session to book it on; its spend is not booked",
                            sessionId, end.reason().name(), end.seq())
                    .field("session", sessionId)
                    .field("seq", end.seq())
                    .field("reason", end.reason().name())
                    .field("inputTokens", u.inputTokens())
                    .field("outputTokens", u.outputTokens())
                    .field("cacheReadTokens", u.cacheReadTokens())
                    .field("cacheWriteTokens", u.cacheWriteTokens())
                    .field("costUsd", u.costUsd())
                    .log();
            return;
        }
        boolean booked = false;
        synchronized (state) {
            if (end.seq() > state.lastBookedSeq) {
                usage.recordSandboxRun(
                        state.projectId,
                        state.lane.wire(),
                        state.model,
                        state.platformFunded,
                        u.inputTokens(),
                        u.outputTokens(),
                        u.cacheReadTokens(),
                        u.cacheWriteTokens(),
                        u.costUsd(),
                        state.subject);
                state.lastBookedSeq = end.seq();
                booked = true;
            }
        }
        if (booked) state.releaseOpen();
        StructuredLog.info(log, Markers.OPS, "agentsession.reply-ended")
                .message(
                        "session %s reply ended (%s) at seq %d, %s",
                        sessionId, end.reason().name(), end.seq(), booked ? "booked" : "already booked")
                .field("session", sessionId)
                .field("seq", end.seq())
                .field("reason", end.reason().name())
                .field("inputTokens", u.inputTokens())
                .field("outputTokens", u.outputTokens())
                .field("cacheReadTokens", u.cacheReadTokens())
                .field("cacheWriteTokens", u.cacheWriteTokens())
                .field("costUsd", u.costUsd())
                .field("booked", booked)
                .log();
    }

    /** A severed connection ends the stream with no exception; only an unparseable frame throws. */
    private final class EventReader implements Iterator<SessionEvent> {
        private final String sessionId;
        private final @Nullable SessionState state;
        private final InputStream body;
        private final BufferedReader lines;
        private final Instant began = clock.instant();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean closedByCaller;
        private final AtomicLong count = new AtomicLong();
        private @Nullable SessionEvent next;

        EventReader(String sessionId, @Nullable SessionState state, InputStream body) {
            this.sessionId = sessionId;
            this.state = state;
            this.body = body;
            this.lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        @Override
        public boolean hasNext() {
            if (next == null && !finished.get()) next = read();
            return next != null;
        }

        @Override
        public SessionEvent next() {
            if (!hasNext()) throw new NoSuchElementException();
            SessionEvent event = Objects.requireNonNull(next);
            next = null;
            return event;
        }

        void closeByCaller() {
            closedByCaller = true;
            finish("caller");
        }

        private @Nullable SessionEvent read() {
            try {
                SessionEvent event = null;
                while (event == null) {
                    Frame frame = frame();
                    if (frame == null) {
                        finish(closedByCaller ? "caller" : "eof");
                        return null;
                    }
                    event = event(frame);
                }
                count.incrementAndGet();
                if (event instanceof SessionEvent.MessageEnd end) ended(sessionId, state, end);
                if (event instanceof SessionEvent.Closed) {
                    closed(sessionId, state);
                    finish("closed");
                }
                return event;
            } catch (IOException e) {
                finish(closedByCaller ? "caller" : "broken");
                return null;
            } catch (RuntimeException e) {
                finish("bad_output");
                throw e;
            }
        }

        private @Nullable SessionEvent event(Frame frame) {
            if (frame.id() == null) {
                throw new TessaryException(AgentRunError.BAD_OUTPUT, "a session event frame without an id");
            }
            long seq;
            JsonNode data;
            try {
                seq = Long.parseLong(frame.id());
                data = mapper.readTree(frame.data());
            } catch (NumberFormatException | JsonProcessingException e) {
                throw new TessaryException(AgentRunError.BAD_OUTPUT, e, "a session event frame that does not parse");
            }
            if (data == null || !data.isObject()) {
                throw new TessaryException(AgentRunError.BAD_OUTPUT, "a session event frame that does not parse");
            }
            Optional<SessionEvent> event = SessionEvent.parse(mapper, seq, frame.type(), data);
            if (event.isEmpty()) {
                StructuredLog.warn(log, Markers.OPS, "agentsession.unknown-event")
                        .message("skipped a session event of unknown type %s", frame.type())
                        .field("session", sessionId)
                        .field("type", frame.type())
                        .log();
            }
            return event.orElse(null);
        }

        /** Null at the end of the body; server-sent-events field rules. */
        private @Nullable Frame frame() throws IOException {
            String id = null;
            String type = "message";
            StringBuilder data = null;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.isEmpty()) {
                    if (id == null && data == null) continue;
                    return new Frame(id, type, data == null ? "" : data.toString());
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "id" -> id = value;
                    case "event" -> type = value;
                    case "data" ->
                        data = data == null
                                ? new StringBuilder(value)
                                : data.append('\n').append(value);
                    default -> {}
                }
            }
            return null;
        }

        private void finish(String ending) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                body.close();
            } catch (IOException e) {
                StructuredLog.debug(log, "agentsession.body-close-failed")
                        .field("session", sessionId)
                        .field("error", e.getClass().getSimpleName())
                        .log();
            }
            StructuredLog.info(log, Markers.OPS, "agentsession.events-ended")
                    .message("session %s event stream ended (%s) after %d events", sessionId, ending, count.get())
                    .field("session", sessionId)
                    .field("events", count.get())
                    .field(
                            "durationMs",
                            Duration.between(began, clock.instant()).toMillis())
                    .field("ending", ending)
                    .log();
        }
    }

    private record Frame(@Nullable String id, String type, String data) {}

    private static boolean unknownSession(int status, String body) {
        return status == 404 && "unknown_session".equals(E2bAgentRunSandbox.kindOf(body));
    }

    private static String drain(InputStream body) {
        try (body) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String sessionPath(String sessionId) {
        return ROUTE + "/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
    }

    private ObjectNode startBody(
            Start start, AgentModels.Choice choice, AgenticCredentialResolver.Credential credential) {
        ObjectNode body = mapper.createObjectNode();
        body.put("lane", start.lane().wire());
        body.put("system_prompt", start.systemPrompt());
        ObjectNode files = body.putObject("files");
        start.files().forEach(files::put);
        body.put("model", choice.model());
        ModelsDevRates.ModelCost modelCost = choice.modelCost();
        if (modelCost != null) body.set("model_cost", modelCost.toOpencodeCost(mapper));
        body.put("provider", choice.provider().name());
        body.set("credential", mapper.valueToTree(credential));
        ArrayNode servers = body.putArray("mcp_servers");
        for (McpServer server : start.mcpServers()) {
            servers.addObject()
                    .put("name", server.name())
                    .put("url", server.url())
                    .put("token", server.token());
        }
        body.put("idle_timeout_ms", start.idleTimeoutMs());
        body.put("max_tool_calls_per_message", start.maxToolCallsPerMessage());
        return body;
    }

    private String launcherUrl() {
        String url = E2bAgentRunSandbox.launcherUrl(props, observerProps);
        if (url.isBlank()) {
            throw new TessaryException(AgentRunError.LAUNCHER_MISCONFIGURED, E2bAgentRunSandbox.NO_LAUNCHER);
        }
        return url;
    }

    private HttpRequest.Builder request(String launcherUrl, String path) {
        return HttpRequest.newBuilder(URI.create(launcherUrl + path))
                .header("Authorization", "Bearer " + E2bAgentRunSandbox.launcherApiKey(props, observerProps));
    }

    private HttpResponse<String> exchange(
            String op, HttpRequest request, String launcherUrl, @Nullable String lane, Duration timeout) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(CommonError.INTERRUPTED, e, "agent session " + op);
        } catch (IOException e) {
            throw unreachable(op, e, launcherUrl, lane, timeout);
        }
    }

    private static TessaryException unreachable(
            String op, IOException e, String launcherUrl, @Nullable String lane, Duration timeout) {
        boolean timedOut = e instanceof HttpTimeoutException;
        StructuredLog.error(log, Markers.OPS, "agentsession.launcher-unreachable")
                .message(
                        "agent launcher at %s did not answer the session %s (%s)",
                        launcherUrl, op, timedOut ? "timed out" : e.getClass().getSimpleName())
                .field("op", op)
                .field("launcherUrl", launcherUrl)
                .field("timedOut", timedOut)
                .field("lane", lane)
                .cause(e)
                .log();
        if (timedOut) return new TessaryException(AgentRunError.TIMED_OUT, e, timeout.toMillis());
        return new TessaryException(
                AgentRunError.LAUNCHER_UNAVAILABLE,
                e,
                "unreachable at " + launcherUrl + " (" + e.getClass().getSimpleName() + ")");
    }

    /** Logged without the body. */
    private static TessaryException refusal(
            String op, int status, String body, String launcherUrl, @Nullable String lane, Duration timeout) {
        AgentRunError failure = status == 429 && "capacity".equals(E2bAgentRunSandbox.kindOf(body))
                ? AgentRunError.CAPACITY
                : E2bAgentRunSandbox.classify(status, body);
        String diag = E2bAgentRunSandbox.describeLauncherError(body);
        StructuredLog.error(log, Markers.OPS, "agentsession.launcher-rejected")
                .message(
                        "agent launcher answered %d to the session %s (%s)%s",
                        status, op, failure.name(), diag.isBlank() ? "" : ": " + diag)
                .field("op", op)
                .field("status", status)
                .field("failure", failure.name())
                .field("lane", lane)
                .field("launcherUrl", launcherUrl)
                .log();
        return switch (failure) {
            case LAUNCHER_MISCONFIGURED, LAUNCHER_UNAVAILABLE ->
                new TessaryException(failure, E2bAgentRunSandbox.launcherDiagnosis(status, launcherUrl, diag, ROUTE));
            case TIMED_OUT -> new TessaryException(failure, timeout.toMillis());
            case CAPACITY, RUN_FAILED, BAD_OUTPUT ->
                new TessaryException(failure, diag.isBlank() ? "status " + status : diag);
        };
    }
}

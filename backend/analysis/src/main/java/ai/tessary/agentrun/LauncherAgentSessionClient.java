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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The launcher-backed {@link AgentSessionClient}: the launcher's {@code /sessions} routes over HTTP/1.1,
 * with the bearer key and the launcher URL every agent lane rides ({@link E2bAgentRunSandbox}).
 *
 * <p>The credential, the launcher key and every MCP token travel to the launcher and nowhere else:
 * never a log line, never an exception message. A refusal maps to {@link AgentRunError} as a one-shot
 * run's does ({@link E2bAgentRunSandbox#classify}), plus {@code CAPACITY} for the launcher's session
 * cap and {@code LAUNCHER_MISCONFIGURED} for a 404 that does not name an unknown session (a launcher
 * with no session routes).
 */
@Component
public class LauncherAgentSessionClient implements AgentSessionClient {

    private static final Logger log = LoggerFactory.getLogger(LauncherAgentSessionClient.class);

    private static final String ROUTE = "/sessions";

    /** The launcher's own limits plus 30 s: 90 s for a sandbox to become ready. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);

    private final AgentRunProperties props;
    private final ObserverProperties observerProps;
    private final AgentModels models;
    private final AgenticCredentialResolver credentials;
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
        this.mapper = mapper;
        this.clock = clock;
        // HTTP/1.1 so a GET carries no h2c upgrade and an event stream is the launcher's chunked body.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** What the client keeps per session: what its spend is booked on. */
    private record SessionState(
            String projectId,
            ModelLane lane,
            String model,
            ModelProvider provider,
            boolean platformFunded,
            LlmUsageAccountant.Subject subject) {}

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
        StructuredLog.info(log, Markers.OPS, "agentsession.started")
                .message(
                        "agent session %s started on lane %s with %d files and %d MCP servers",
                        sessionId,
                        start.lane().wire(),
                        start.files().size(),
                        start.mcpServers().size())
                .field("project", start.projectId())
                .field("lane", start.lane().wire())
                .field("subjectKind", start.subject().kind())
                .field("subjectId", start.subject().id())
                .field("session", sessionId)
                .field("files", start.files().size())
                .field("mcpServers", start.mcpServers().size())
                .field("durationMs", Duration.between(began, clock.instant()).toMillis())
                .log();
        return new Session(sessionId);
    }

    @Override
    public Stream<SessionEvent> events(String sessionId, long afterSeq) {
        String launcherUrl = launcherUrl();
        SessionState state = sessions.get(sessionId);
        String lane = state == null ? null : state.lane().wire();
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

    /** A session that ended: the client forgets it. */
    private void closed(String sessionId, @Nullable SessionState state) {
        if (state != null) sessions.remove(sessionId, state);
    }

    /**
     * Reads the launcher's event frames one at a time, as they arrive, and runs each event's side
     * effects before handing it out. A severed connection ends the stream with no exception; only a
     * frame that does not parse is thrown.
     */
    private final class EventReader implements Iterator<SessionEvent> {
        private final String sessionId;
        private final @Nullable SessionState state;
        private final InputStream body;
        private final BufferedReader lines;
        private final Instant began = clock.instant();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean closedByCaller;
        private long count;
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
                while (true) {
                    Frame frame = frame();
                    if (frame == null) {
                        finish(closedByCaller ? "caller" : "eof");
                        return null;
                    }
                    SessionEvent event = event(frame);
                    if (event == null) continue;
                    count++;
                    if (event instanceof SessionEvent.Closed) {
                        closed(sessionId, state);
                        finish("closed");
                    }
                    return event;
                }
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
                throw new TessaryException(AgentRunError.BAD_OUTPUT, "a session event frame that does not parse");
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

        /** The next frame, or null at the end of the body. Field rules are the server-sent events ones. */
        private @Nullable Frame frame() throws IOException {
            String id = null;
            String type = "message";
            StringBuilder data = null;
            String line;
            while ((line = lines.readLine()) != null) {
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
                // the connection is gone either way; nothing more is read from it
            }
            StructuredLog.info(log, Markers.OPS, "agentsession.events-ended")
                    .message("session %s event stream ended (%s) after %d events", sessionId, ending, count)
                    .field("session", sessionId)
                    .field("events", count)
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

    /** One request with a string answer; a transport failure or an interrupt is thrown as its {@link AgentRunError}. */
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

    /** A non-2xx answer as its {@link AgentRunError}, logged without the body. */
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

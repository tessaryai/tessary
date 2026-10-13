// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ModelsDevProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * {@link LauncherAgentSessionClient} against a {@link FakeLauncher} on a loopback socket, through the
 * real {@code HttpClient}: the start body the launcher receives, which {@link AgentRunError} each
 * answer maps to, and when the credential is claimed and released.
 */
@ExtendWith(MockitoExtension.class)
class LauncherAgentSessionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECT = "proj";
    private static final String LAUNCHER_KEY = "launcher-k";
    private static final String MCP_TOKEN = "tsy_a_secret";

    /** The real lookup over the bundled models.dev copy; a blank URL never fetches. */
    private static final ModelsDevRates RATES = bundledRates();

    private static ModelsDevRates bundledRates() {
        ModelsDevProperties p = new ModelsDevProperties();
        p.setUrl("");
        return new ModelsDevRates(MAPPER, p);
    }

    private static final AgenticCredentialResolver.Credential START_CRED = new AgenticCredentialResolver.Credential(
            ModelProvider.GROK, "xai-key", null, null, null, null, null, false, null, "start");
    private static final LlmUsageAccountant.Subject SUBJECT = new LlmUsageAccountant.Subject("work_item", "w1");
    private static final AgentSessionClient.Start START = new AgentSessionClient.Start(
            PROJECT,
            ModelLane.AUTHORING,
            "You help.",
            Map.of("notes.md", "read me"),
            List.of(
                    new AgentSessionClient.McpServer("platform", "https://api.example/mcp", MCP_TOKEN),
                    new AgentSessionClient.McpServer("extra", "https://api.example/mcp/extra/1", MCP_TOKEN)),
            600_000L,
            30,
            SUBJECT);

    @Mock
    ProjectModelSettings modelSettings;

    @Mock
    AgenticCredentialResolver credentials;

    @Mock
    LlmUsageAccountant usage;

    private FakeLauncher fake;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void startTheLauncher() throws IOException {
        fake = FakeLauncher.start();
        logger = (Logger) LoggerFactory.getLogger(LauncherAgentSessionClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopTheLauncher() throws IOException {
        logger.detachAppender(appender);
        fake.close();
    }

    private LauncherAgentSessionClient client(String launcherUrl) {
        AgentRunProperties props = new AgentRunProperties();
        props.setLauncherUrl(launcherUrl);
        props.setLauncherApiKey(launcherUrl.isEmpty() ? "" : LAUNCHER_KEY);
        ObserverProperties observer = new ObserverProperties();
        observer.getAgentic().setLauncherUrl("");
        observer.getAgentic().setLauncherApiKey("");
        return new LauncherAgentSessionClient(
                props,
                observer,
                new AgentModels(modelSettings, observer, RATES),
                credentials,
                usage,
                MAPPER,
                Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));
    }

    private LauncherAgentSessionClient client() {
        return client(fake.url());
    }

    private void aGrokLane() {
        when(modelSettings.resolveAgenticModel(PROJECT, ModelLane.AUTHORING))
                .thenReturn(Optional.of(
                        new ProjectModelSettings.ResolvedAgenticModel(ModelProvider.GROK, "grok-4.6", "xai/grok-4.6")));
    }

    private void aGrokLaneWithItsCredential() {
        aGrokLane();
        when(credentials.resolve(PROJECT, ModelProvider.GROK)).thenReturn(START_CRED);
    }

    private static String closedPortUrl() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    // ---- start --------------------------------------------------------------------------------------

    /**
     * Catches a misspelled field, a model or provider resolved differently from a one-shot run, and a
     * credential held for the session's whole life instead of only until the launcher answered.
     */
    @Test
    void startPostsTheLanesModelCredentialAndServersAndFreesTheCredentialOnceAnswered() throws Exception {
        aGrokLaneWithItsCredential();
        fake.answer("POST", "/sessions", 201, "{\"session_id\":\"s-1\"}");

        AgentSessionClient.Session session = client().start(START);

        assertEquals(new AgentSessionClient.Session("s-1"), session);
        assertEquals(1, fake.seen().size());
        FakeLauncher.Seen seen = fake.seen().getFirst();
        assertEquals("POST", seen.method());
        assertEquals("/sessions", seen.path());
        assertEquals("Bearer " + LAUNCHER_KEY, seen.headers().get("authorization"));
        ObjectNode expected = (ObjectNode) MAPPER.readTree("""
                {"lane":"authoring","system_prompt":"You help.","files":{"notes.md":"read me"},
                 "model":"grok-4.6","provider":"GROK",
                 "credential":{"provider":"GROK","api_key":"xai-key","platform_funded":false},
                 "mcp_servers":[{"name":"platform","url":"https://api.example/mcp","token":"tsy_a_secret"},
                                {"name":"extra","url":"https://api.example/mcp/extra/1","token":"tsy_a_secret"}],
                 "idle_timeout_ms":600000,"max_tool_calls_per_message":30}
                """);
        expected.set(
                "model_cost",
                MAPPER.readTree(RATES.cost("xai/grok-4.6")
                        .orElseThrow()
                        .toOpencodeCost(MAPPER)
                        .toString()));
        assertEquals(expected, MAPPER.readTree(seen.body()));
        verify(credentials).release(START_CRED);
    }

    /**
     * Catches a failed start that keeps a Tessary AI run slot: the credential is freed whatever the
     * launcher answered, or when nothing listens at all (status 0 here).
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "502 | {\"kind\":\"bad_request\",\"detail\":\"session.js: model must be a non-empty string\"} | RUN_FAILED",
                "502 | {\"kind\":\"orchestration\"}                                                          | LAUNCHER_UNAVAILABLE",
                "0   | ''                                                                                 | LAUNCHER_UNAVAILABLE"
            })
    void aStartTheLauncherRefusesOrCannotTakeStillFreesTheCredential(int status, String body, AgentRunError expected)
            throws Exception {
        aGrokLaneWithItsCredential();
        fake.answer("POST", "/sessions", status, body);
        LauncherAgentSessionClient client = status == 0 ? client(closedPortUrl()) : client();

        TessaryException ex = assertThrows(TessaryException.class, () -> client.start(START));

        assertEquals(expected, ex.error());
        verify(credentials).release(START_CRED);
    }

    /** Catches a full launcher read as a broken run ({@code RUN_FAILED}), which a caller would not retry. */
    @Test
    void aLauncherAtItsCapIsCapacity() {
        aGrokLaneWithItsCredential();
        fake.answer("POST", "/sessions", 429, "{\"error\":\"sandbox session capacity reached\",\"kind\":\"capacity\"}");

        TessaryException ex = assertThrows(TessaryException.class, () -> client().start(START));

        assertEquals(AgentRunError.CAPACITY, ex.error());
        verify(credentials).release(START_CRED);
    }

    /** Catches an older launcher image read as a closed session, so the caller would replace it forever. */
    @Test
    void aLauncherWithoutSessionRoutesIsMisconfigured() {
        aGrokLaneWithItsCredential();
        fake.answer("POST", "/sessions", 404, "{\"error\":\"not found\"}");

        TessaryException ex = assertThrows(TessaryException.class, () -> client().start(START));

        assertEquals(AgentRunError.LAUNCHER_MISCONFIGURED, ex.error());
        assertEquals(
                "The agent launcher refused the run: status 404 from " + fake.url()
                        + ", no /sessions route; the launcher image is older than this backend",
                ex.getMessage());
    }

    /** Catches a sandbox started with no credential. */
    @Test
    void noCredentialStartsNoSandbox() {
        aGrokLane();
        when(credentials.resolve(PROJECT, ModelProvider.GROK))
                .thenThrow(new TessaryException(ModelConfigError.MISSING_CREDENTIALS, ModelProvider.GROK));

        TessaryException ex = assertThrows(TessaryException.class, () -> client().start(START));

        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
        assertEquals(List.of(), fake.seen());
    }

    /** Catches a run slot claimed for a start that cannot be sent anywhere. */
    @Test
    void noLauncherIsMisconfiguredBeforeAnythingIsResolved() {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> client("").start(START));

        assertEquals(AgentRunError.LAUNCHER_MISCONFIGURED, ex.error());
        verifyNoInteractions(credentials, modelSettings);
        assertEquals(List.of(), fake.seen());
    }

    /** Catches a token or a credential in a log line, in a thrown message, or in a record's {@code toString}. */
    @Test
    void tokensAndTheCredentialNeverReachALogLineOrAString() {
        aGrokLaneWithItsCredential();
        fake.answer("POST", "/sessions", 502, "{\"kind\":\"script_exit\",\"detail\":\"x\"}");

        TessaryException ex = assertThrows(TessaryException.class, () -> client().start(START));

        assertFalse(appender.list.isEmpty(), "the refusal is logged");
        List<String> rendered = new java.util.ArrayList<>();
        rendered.add(String.valueOf(ex.getMessage()));
        rendered.add(START.toString());
        for (ILoggingEvent event : appender.list) {
            rendered.add(event.getFormattedMessage() + " " + event.getKeyValuePairs() + " "
                    + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage()));
        }
        for (String line : rendered) {
            for (String secret : List.of("xai-key", MCP_TOKEN, LAUNCHER_KEY)) {
                assertFalse(line.contains(secret), secret + " leaked into: " + line);
            }
        }
    }

    // ---- events -------------------------------------------------------------------------------------

    private static final String USAGE_JSON = "{\"input_tokens\":1200,\"output_tokens\":80,"
            + "\"cache_read_input_tokens\":1000,\"cache_creation_input_tokens\":200,\"cost_usd\":0.0123}";
    private static final AgentSpanTelemetry.AgentUsage USAGE =
            new AgentSpanTelemetry.AgentUsage(1200, 80, 1000, 200, new BigDecimal("0.0123"));

    private LauncherAgentSessionClient aStartedSession() {
        aGrokLaneWithItsCredential();
        fake.answer("POST", "/sessions", 201, "{\"session_id\":\"s-1\"}");
        LauncherAgentSessionClient client = client();
        client.start(START);
        return client;
    }

    private static List<SessionEvent> read(LauncherAgentSessionClient client, long afterSeq) {
        try (Stream<SessionEvent> events = client.events("s-1", afterSeq)) {
            return events.toList();
        }
    }

    /** Catches a dropped or mis-mapped field, and a JSON {@code null} server read as the string "null". */
    @Test
    void eventsYieldEveryTypeInOrder() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"Hel\"}"),
                FakeLauncher.frame(
                        2,
                        "tool_start",
                        "{\"call_id\":\"c1\",\"server\":\"platform\",\"tool\":\"search_traces\","
                                + "\"input_summary\":\"{\\\"q\\\":\\\"x\\\"}\"}"),
                FakeLauncher.frame(
                        3,
                        "tool_start",
                        "{\"call_id\":\"c2\",\"server\":null,\"tool\":\"read\",\"input_summary\":\"{}\"}"),
                FakeLauncher.frame(
                        4,
                        "tool_end",
                        "{\"call_id\":\"c1\",\"result_summary\":\"3 traces\",\"bytes\":812,\"duration_ms\":40,"
                                + "\"ok\":true}"),
                FakeLauncher.frame(5, "error", "{\"kind\":\"agent\",\"detail\":\"ProviderError: overloaded\"}"),
                FakeLauncher.frame(6, "message_end", "{\"usage\":" + USAGE_JSON + ",\"reason\":\"done\"}"),
                FakeLauncher.frame(7, "closed", "{\"reason\":\"idle\"}"));

        List<SessionEvent> events = read(client, 0);

        assertEquals(
                List.of(
                        new SessionEvent.Text(1, "Hel"),
                        new SessionEvent.ToolStart(2, "c1", "platform", "search_traces", "{\"q\":\"x\"}"),
                        new SessionEvent.ToolStart(3, "c2", null, "read", "{}"),
                        new SessionEvent.ToolEnd(4, "c1", "3 traces", 812, 40, true),
                        new SessionEvent.Failure(5, "agent", "ProviderError: overloaded"),
                        new SessionEvent.MessageEnd(6, USAGE, SessionEvent.EndReason.DONE),
                        new SessionEvent.Closed(7, SessionEvent.CloseReason.IDLE)),
                events);
    }

    /** Catches a reconnect that replays from the start or ignores {@code after}. */
    @Test
    void eventsResumeAfterTheGivenSequence() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.events(
                "/sessions/s-1/events?after=4",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(5, "text", "{\"delta\":\"a\"}"),
                FakeLauncher.frame(6, "text", "{\"delta\":\"b\"}"));

        List<SessionEvent> events = read(client, 4);

        assertEquals(List.of(new SessionEvent.Text(5, "a"), new SessionEvent.Text(6, "b")), events);
        FakeLauncher.Seen seen = fake.seen().getLast();
        assertEquals("GET", seen.method());
        assertEquals("/sessions/s-1/events?after=4", seen.path());
        assertEquals("Bearer " + LAUNCHER_KEY, seen.headers().get("authorization"));
        assertEquals("text/event-stream", seen.headers().get("accept"));
    }

    /** Catches a reader that buffers the whole body, so the page would see nothing until the session ends. */
    @Test
    void eventsArriveWhileTheStreamIsOpen() throws Exception {
        LauncherAgentSessionClient client = aStartedSession();
        FakeLauncher.LiveEvents live = fake.liveEvents("/sessions/s-1/events?after=0");
        live.push(FakeLauncher.frame(1, "text", "{\"delta\":\"Hel\"}"));

        try (Stream<SessionEvent> events = client.events("s-1", 0)) {
            Iterator<SessionEvent> it = events.iterator();
            CompletableFuture<SessionEvent> first = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> first.complete(it.next()));

            assertEquals(new SessionEvent.Text(1, "Hel"), first.get(5, TimeUnit.SECONDS));

            live.push(FakeLauncher.frame(2, "text", "{\"delta\":\"lo\"}"));
            live.end();
            assertEquals(new SessionEvent.Text(2, "lo"), it.next());
            assertFalse(it.hasNext());
        }
    }

    /** Catches a broken connection reported as a closed session, or thrown at the relay thread. */
    @Test
    void aCutStreamEndsWithoutClosedSoTheCallerResumes() {
        LauncherAgentSessionClient client = aStartedSession();
        FakeLauncher.LiveEvents live = fake.liveEvents("/sessions/s-1/events?after=0");
        live.push(FakeLauncher.frame(1, "text", "{\"delta\":\"a\"}"));
        live.push(FakeLauncher.frame(2, "text", "{\"delta\":\"b\"}"));

        try (Stream<SessionEvent> events = client.events("s-1", 0)) {
            Iterator<SessionEvent> it = events.iterator();
            assertEquals(new SessionEvent.Text(1, "a"), it.next());
            assertEquals(new SessionEvent.Text(2, "b"), it.next());

            live.cut();

            assertFalse(it.hasNext());
        }
    }

    /** Catches a launcher restart that leaves the caller waiting on a session that is gone. */
    @Test
    void aSessionTheLauncherNoLongerKnowsEndsWithClosed() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.answer(
                "GET", "/sessions/s-1/events?after=4", 404, "{\"error\":\"not found\",\"kind\":\"unknown_session\"}");

        List<SessionEvent> events = read(client, 4);

        assertEquals(List.of(new SessionEvent.Closed(4, SessionEvent.CloseReason.UNKNOWN)), events);
        int requests = fake.seen().size();
        assertEquals(AgentSessionClient.SendOutcome.CLOSED, client.send("s-1", "hi"));
        assertEquals(requests, fake.seen().size(), "a closed session sends nothing");
    }

    /** Catches a stale reader that silently skips the events the launcher no longer holds. */
    @Test
    void aStreamPastTheBufferIsRunFailed() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.answer(
                "GET", "/sessions/s-1/events?after=0", 410, "{\"error\":\"events no longer held\",\"kind\":\"gone\"}");

        TessaryException ex = assertThrows(TessaryException.class, () -> read(client, 0));

        assertEquals(AgentRunError.RUN_FAILED, ex.error());
    }

    /** Catches a newer launcher's event type that kills the relay thread. */
    @Test
    void anUnknownEventTypeIsSkipped() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"a\"}"),
                FakeLauncher.frame(2, "thinking", "{\"x\":1}"),
                FakeLauncher.frame(3, "closed", "{\"reason\":\"stopped\"}"));

        List<SessionEvent> events = read(client, 0);

        assertEquals(
                List.of(new SessionEvent.Text(1, "a"), new SessionEvent.Closed(3, SessionEvent.CloseReason.STOPPED)),
                events);
    }

    /** Catches a truncated frame yielded as an empty delta. */
    @Test
    void aFrameThatDoesNotParseIsBadOutput() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.events(
                "/sessions/s-1/events?after=0", FakeLauncher.Ending.END, FakeLauncher.frame(1, "text", "{\"delta\":"));

        TessaryException ex = assertThrows(TessaryException.class, () -> read(client, 0));

        assertEquals(AgentRunError.BAD_OUTPUT, ex.error());
    }

    // ---- replies, credentials and booking -----------------------------------------------------------

    private static final AgenticCredentialResolver.Credential REPLY_CRED = new AgenticCredentialResolver.Credential(
            ModelProvider.GROK, "xai-key", null, null, null, null, null, false, null, "reply");
    private static final AgenticCredentialResolver.Credential REPLY2_CRED = new AgenticCredentialResolver.Credential(
            ModelProvider.GROK, "xai-key", null, null, null, null, null, false, null, "reply2");

    private LauncherAgentSessionClient aStartedSessionWith(
            AgenticCredentialResolver.Credential startCred, AgenticCredentialResolver.Credential... replies) {
        aGrokLane();
        when(credentials.resolve(PROJECT, ModelProvider.GROK)).thenReturn(startCred, replies);
        fake.answer("POST", "/sessions", 201, "{\"session_id\":\"s-1\"}");
        LauncherAgentSessionClient client = client();
        client.start(START);
        return client;
    }

    private static String messageEnd(String reason) {
        return "{\"usage\":" + USAGE_JSON + ",\"reason\":\"" + reason + "\"}";
    }

    private void verifyBookedOnce(boolean platformFunded) {
        verify(usage)
                .recordSandboxRun(
                        "proj",
                        "authoring",
                        "grok-4.6",
                        platformFunded,
                        1200L,
                        80L,
                        1000L,
                        200L,
                        new BigDecimal("0.0123"),
                        SUBJECT);
    }

    /** Catches a run slot freed when the message is accepted (Tessary AI over-commits) or never freed. */
    @Test
    void aReplyHoldsItsCredentialUntilItsMessageEnd() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"a\"}"),
                FakeLauncher.frame(2, "message_end", messageEnd("done")),
                FakeLauncher.frame(3, "closed", "{\"reason\":\"idle\"}"));

        assertEquals(AgentSessionClient.SendOutcome.ACCEPTED, client.send("s-1", "hello"));

        FakeLauncher.Seen message = fake.seen().getLast();
        assertEquals("POST /sessions/s-1/messages", message.method() + " " + message.path());
        assertEquals("Bearer " + LAUNCHER_KEY, message.headers().get("authorization"));
        assertEquals("{\"text\":\"hello\"}", message.body());
        verify(credentials, times(2)).resolve(PROJECT, ModelProvider.GROK);
        verify(credentials, never()).release(REPLY_CRED);
        try (Stream<SessionEvent> events = client.events("s-1", 0)) {
            Iterator<SessionEvent> it = events.iterator();
            assertEquals(1, it.next().seq());
            verify(credentials, never()).release(REPLY_CRED);
            assertEquals(2, it.next().seq());
            verify(credentials).release(REPLY_CRED);
            assertEquals(3, it.next().seq());
            verify(credentials).release(REPLY_CRED);
        }
    }

    /**
     * Catches a double release (the credential's lease released twice), and a second reply started while the
     * aborted one still runs.
     */
    @Test
    void anAbortFreesTheReplyAndItsMessageEndFreesNothingMore() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED, REPLY2_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.answer("POST", "/sessions/s-1/abort", 204, null);
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "message_end", messageEnd("aborted")));
        client.send("s-1", "hello");

        client.abort("s-1");

        FakeLauncher.Seen abort = fake.seen().getLast();
        assertEquals("POST /sessions/s-1/abort", abort.method() + " " + abort.path());
        verify(credentials).release(REPLY_CRED);
        int requests = fake.seen().size();
        assertEquals(AgentSessionClient.SendOutcome.BUSY, client.send("s-1", "again"));
        assertEquals(requests, fake.seen().size(), "a busy session is not asked");
        verify(credentials, times(2)).resolve(PROJECT, ModelProvider.GROK);

        read(client, 0);

        verify(credentials).release(REPLY_CRED);
        assertEquals(AgentSessionClient.SendOutcome.ACCEPTED, client.send("s-1", "again"));
        verify(credentials, times(3)).resolve(PROJECT, ModelProvider.GROK);
    }

    /** Catches a sandbox death that keeps the run slot until the lease runs out. */
    @Test
    void aClosedSessionFreesAnOpenReply() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "closed", "{\"reason\":\"failed\"}"));
        client.send("s-1", "hello");

        read(client, 0);

        verify(credentials).release(REPLY_CRED);
        assertEquals(AgentSessionClient.SendOutcome.CLOSED, client.send("s-1", "again"));
        verify(credentials, times(2)).resolve(PROJECT, ModelProvider.GROK);
    }

    /** Catches a claim leaked on a message the launcher never ran. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "409 | {\"error\":\"session busy\",\"kind\":\"busy\"}               | BUSY",
                "404 | {\"error\":\"not found\",\"kind\":\"unknown_session\"}   | CLOSED",
                "502 | {\"kind\":\"orchestration\"}                               | LAUNCHER_UNAVAILABLE"
            })
    void aRefusedMessageFreesItsClaimAtOnce(int status, String body, String expected) {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", status, body);

        if (expected.equals("LAUNCHER_UNAVAILABLE")) {
            TessaryException ex = assertThrows(TessaryException.class, () -> client.send("s-1", "hello"));
            assertEquals(AgentRunError.LAUNCHER_UNAVAILABLE, ex.error());
        } else {
            assertEquals(AgentSessionClient.SendOutcome.valueOf(expected), client.send("s-1", "hello"));
        }

        verify(credentials).release(REPLY_CRED);
    }

    /** Catches spend booked on the wrong lane, model or subject, or a reply's spend lost. */
    @Test
    void eachNewMessageEndBooksOneRowOnTheCallersLaneAndSubject() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "message_end", messageEnd("done")),
                FakeLauncher.frame(2, "text", "{\"delta\":\"a\"}"),
                FakeLauncher.frame(
                        3,
                        "message_end",
                        "{\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"cache_read_input_tokens\":0,"
                                + "\"cache_creation_input_tokens\":0,\"cost_usd\":0.0021},\"reason\":\"limit\"}"));

        read(client, 0);

        verifyBookedOnce(false);
        verify(usage)
                .recordSandboxRun(
                        "proj", "authoring", "grok-4.6", false, 10L, 2L, 0L, 0L, new BigDecimal("0.0021"), SUBJECT);
        verifyNoMoreInteractions(usage);
    }

    /** Catches the same reply billed twice after a reconnect. */
    @Test
    void aMessageEndReplayedAfterAReconnectBooksNothing() {
        LauncherAgentSessionClient client = aStartedSession();
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"a\"}"),
                FakeLauncher.frame(2, "message_end", messageEnd("done")));
        fake.events(
                "/sessions/s-1/events?after=1",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(2, "message_end", messageEnd("done")),
                FakeLauncher.frame(3, "closed", "{\"reason\":\"idle\"}"));

        read(client, 0);
        read(client, 1);

        verifyBookedOnce(false);
        verifyNoMoreInteractions(usage);
    }

    /** Catches Tessary AI spend booked on the org's own key. */
    @Test
    void aPlatformFundedSessionBooksAsPlatformFunded() {
        LauncherAgentSessionClient client = aStartedSessionWith(new AgenticCredentialResolver.Credential(
                ModelProvider.GROK, null, null, null, null, null, null, true, "e2b-secret", "start"));
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "message_end", messageEnd("done")));

        read(client, 0);

        verifyBookedOnce(true);
    }

    /** Catches a stop that loses the reply's spend, or frees the run slot twice. */
    @Test
    void stopFreesTheReplyAndStillBooksItsLastMessageEnd() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.answer("DELETE", "/sessions/s-1", 204, null);
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "message_end", messageEnd("aborted")),
                FakeLauncher.frame(2, "closed", "{\"reason\":\"stopped\"}"));
        client.send("s-1", "hello");

        client.stop("s-1");

        FakeLauncher.Seen stop = fake.seen().getLast();
        assertEquals("DELETE /sessions/s-1", stop.method() + " " + stop.path());
        assertEquals("Bearer " + LAUNCHER_KEY, stop.headers().get("authorization"));
        verify(credentials).release(REPLY_CRED);

        read(client, 0);

        verifyBookedOnce(false);
        verify(credentials).release(REPLY_CRED);
    }

    /** Catches a cleanup path that throws on a session the launcher already dropped. */
    @Test
    void stopAndAbortOnAGoneSessionAreQuiet() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        String gone = "{\"error\":\"not found\",\"kind\":\"unknown_session\"}";
        fake.answer("POST", "/sessions/s-1/abort", 404, gone);
        fake.answer("DELETE", "/sessions/s-1", 404, gone);
        client.send("s-1", "hello");

        client.abort("s-1");
        client.stop("s-1");

        verify(credentials).release(REPLY_CRED);
        assertEquals(
                List.of(
                        "POST /sessions",
                        "POST /sessions/s-1/messages",
                        "POST /sessions/s-1/abort",
                        "DELETE /sessions/s-1"),
                fake.seen().stream().map(r -> r.method() + " " + r.path()).toList());
    }
    /**
     * Catches a replayed {@code message_end} that frees the newer reply's credential, so a Tessary AI
     * run slot is over-committed while that reply still runs.
     */
    @Test
    void aMessageEndReplayedWhileANewerReplyRunsFreesNothing() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED, REPLY2_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"a\"}"),
                FakeLauncher.frame(2, "message_end", messageEnd("done")));
        fake.events(
                "/sessions/s-1/events?after=1",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(2, "message_end", messageEnd("done")));
        assertEquals(AgentSessionClient.SendOutcome.ACCEPTED, client.send("s-1", "one"));
        read(client, 0);
        verify(credentials).release(REPLY_CRED);
        assertEquals(AgentSessionClient.SendOutcome.ACCEPTED, client.send("s-1", "two"));

        read(client, 1);

        verify(credentials, never()).release(REPLY2_CRED);
        assertEquals(AgentSessionClient.SendOutcome.BUSY, client.send("s-1", "three"));
        verifyBookedOnce(false);
        verifyNoMoreInteractions(usage);
    }

    /**
     * Catches the last reply's spend lost when an abort finds the session already closing: the 404
     * must not drop what the reconnected stream still has to book.
     */
    @Test
    void aMessageEndReadAfterAnAbortFoundTheSessionClosingIsStillBooked() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.answer("POST", "/sessions/s-1/abort", 404, "{\"error\":\"not found\",\"kind\":\"unknown_session\"}");
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"a\"}"));
        fake.events(
                "/sessions/s-1/events?after=1",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(2, "message_end", messageEnd("aborted")),
                FakeLauncher.frame(3, "closed", "{\"reason\":\"idle\"}"));
        client.send("s-1", "hello");
        read(client, 0);

        client.abort("s-1");

        verify(credentials).release(REPLY_CRED);
        int requests = fake.seen().size();
        assertEquals(AgentSessionClient.SendOutcome.CLOSED, client.send("s-1", "again"));
        assertEquals(requests, fake.seen().size(), "a session the launcher closed is not asked again");
        read(client, 1);
        verifyBookedOnce(false);
        verify(credentials).release(REPLY_CRED);
    }

    /**
     * Catches spend lost silently: a {@code message_end} the client has no session for (as after a
     * backend restart) cannot be booked, so it is a warning that carries the cost.
     */
    @Test
    void aMessageEndThisClientCannotBookIsAWarningWithItsCost() {
        LauncherAgentSessionClient client = client();
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "message_end", messageEnd("aborted")));

        read(client, 0);

        verifyNoInteractions(usage);
        List<ILoggingEvent> unbooked = logged("agentsession.reply-unbooked");
        assertEquals(1, unbooked.size());
        assertEquals(Level.WARN, unbooked.getFirst().getLevel());
        assertEquals("0.0123", String.valueOf(fields(unbooked.getFirst()).get("costUsd")));
        assertEquals(List.of(), logged("agentsession.reply-ended"));
    }

    /**
     * Catches a message accepted with no trace in the log, so a reply whose {@code message_end} never
     * comes cannot be found, and a start whose prompt and files sizes are unknown.
     */
    @Test
    void startSendAbortAndStopEachLogTheirOutcomeAndSize() {
        LauncherAgentSessionClient client = aStartedSessionWith(START_CRED, REPLY_CRED);
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.answer("POST", "/sessions/s-1/abort", 204, null);
        fake.answer("DELETE", "/sessions/s-1", 204, null);

        client.send("s-1", "h\u00e9llo");
        client.send("s-1", "again");
        client.abort("s-1");
        client.stop("s-1");

        Map<String, Object> started = fields(logged("agentsession.started").getFirst());
        assertEquals("9", String.valueOf(started.get("systemPromptBytes")));
        assertEquals("7", String.valueOf(started.get("fileBytes")));
        List<Map<String, Object>> messages = logged("agentsession.message").stream()
                .map(LauncherAgentSessionClientTest::fields)
                .toList();
        assertEquals(
                List.of("ACCEPTED", "BUSY"),
                messages.stream().map(m -> m.get("outcome")).toList());
        assertEquals(
                List.of("6", "5"),
                messages.stream().map(m -> String.valueOf(m.get("bytes"))).toList());
        assertEquals("s-1", messages.getFirst().get("session"));
        assertEquals(
                "aborted", fields(logged("agentsession.aborted").getFirst()).get("outcome"));
        assertEquals(
                "stopped", fields(logged("agentsession.stopped").getFirst()).get("outcome"));
    }

    private List<ILoggingEvent> logged(String event) {
        return appender.list.stream()
                .filter(e -> event.equals(fields(e).get("event")))
                .toList();
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> out = new HashMap<>();
        if (event.getKeyValuePairs() != null) event.getKeyValuePairs().forEach(kv -> out.put(kv.key, kv.value));
        return out;
    }
}

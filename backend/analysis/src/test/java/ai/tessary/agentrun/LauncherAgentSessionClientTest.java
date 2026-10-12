// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import ai.tessary.usage.LlmUsageAccountant;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.LoopbackHttpStub;
import ai.tessary.testsupport.ScriptedHttpClient;
import ai.tessary.usage.LlmUsageAccountant;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * {@link E2bAgentRunSandbox} against a scripted HTTP client: the request the launcher receives, what
 * never reaches a log, how a 2xx envelope becomes a {@link AgentRunSandbox.Result}, which
 * {@link AgentRunError} each failure maps to, and that the ledger is booked whether the run finished
 * or failed.
 */
@ExtendWith(MockitoExtension.class)
class E2bAgentRunSandboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "launcher-secret-k";
    private static final String MCP_TOKEN = "tsy_a_secrettoken";
    private static final String CLONE_URL = "https://x-access-token:ghs_clonetoken@github.com/acme/app.git";
    private static final AgenticCredentialResolver.Credential CREDENTIAL = new AgenticCredentialResolver.Credential(
            ModelProvider.ANTHROPIC, "sk-ant-secretkey", null, null, null, null, null);
    private static final LlmUsageAccountant.Subject SUBJECT = new LlmUsageAccountant.Subject("classifier_draft", "d1");

    @Mock
    LlmUsageAccountant usage;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(E2bAgentRunSandbox.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static AgentRunProperties props(String launcherUrl) {
        AgentRunProperties p = new AgentRunProperties();
        p.setLauncherUrl(launcherUrl);
        p.setLauncherApiKey(API_KEY);
        return p;
    }

    private static ObserverProperties observer(String launcherUrl, String apiKey) {
        ObserverProperties p = new ObserverProperties();
        p.getAgentic().setLauncherUrl(launcherUrl);
        p.getAgentic().setLauncherApiKey(apiKey);
        return p;
    }

    private E2bAgentRunSandbox sandbox(AgentRunProperties props, ObserverProperties observer, HttpClient client) {
        return new E2bAgentRunSandbox(props, observer, usage, OpenTelemetry.noop(), MAPPER, client);
    }

    private E2bAgentRunSandbox sandbox(HttpClient client) {
        return sandbox(props("http://launcher"), observer("", ""), client);
    }

    private static AgentRunSandbox.Request request(
            @Nullable String cloneUrl, @Nullable String headSha, @Nullable String jsonSchema) {
        return new AgentRunSandbox.Request(
                "proj",
                "authoring",
                "You write classifiers.",
                "Write one for timeouts.",
                Map.of("brief.md", "the description"),
                jsonSchema,
                cloneUrl,
                headSha,
                "claude-sonnet-5-5",
                "anthropic/claude-sonnet-5-5",
                "ANTHROPIC",
                CREDENTIAL,
                "https://api.example/mcp",
                MCP_TOKEN,
                900_000L,
                40,
                SUBJECT);
    }

    private static AgentRunSandbox.Request fullRequest() {
        return request(CLONE_URL, "abc123", "{\"type\":\"object\",\"required\":[\"builder\"]}");
    }

    private static String envelope(Map<String, Object> fields) throws Exception {
        return MAPPER.writeValueAsString(
                Map.of("raw", MAPPER.writeValueAsString(fields), "startMs", 0, "turns", List.of()));
    }

    private static String errorBody(String kind) throws Exception {
        return MAPPER.writeValueAsString(Map.of("kind", kind, "detail", "the launcher's diagnosis"));
    }

    // ---- the request --------------------------------------------------------------------------------

    /** A missing or misspelled field is a run the launcher refuses, or starts without the repo or the door. */
    @Test
    void postsTriagesBodyPlusTheCloneAndTheSystemPrompt() throws Exception {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, envelope(Map.of("result", "ok")));

        sandbox(client).run(fullRequest());

        assertEquals("http://launcher/authoring", client.lastRequest().uri().toString());
        assertEquals(
                List.of("Bearer " + API_KEY), client.lastRequest().headers().allValues("Authorization"));
        JsonNode expected = MAPPER.readTree(String.format(Locale.ROOT, """
                {"clone_url":"%s","head_sha":"abc123",
                 "files":{"brief.md":"the description"},
                 "system_prompt":"You write classifiers.",
                 "prompt":"Write one for timeouts.",
                 "json_schema":"{\\"type\\":\\"object\\",\\"required\\":[\\"builder\\"]}",
                 "model":"claude-sonnet-5-5","provider":"ANTHROPIC",
                 "credential":{"provider":"ANTHROPIC","api_key":"sk-ant-secretkey","platform_funded":false},
                 "mcp":{"url":"https://api.example/mcp","token":"%s"},
                 "timeout_ms":900000,"max_turns":40}
                """, CLONE_URL, MCP_TOKEN));
        assertEquals(expected, MAPPER.readTree(client.lastBody()));
    }

    /** The VM script branches on the clone fields' presence, and a null schema must not become the string "null". */
    @Test
    void omitsTheCloneAndTheSchemaWhenTheRequestHasNone() throws Exception {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, envelope(Map.of("result", "ok")));

        sandbox(client).run(request(null, null, null));

        JsonNode body = MAPPER.readTree(client.lastBody());
        assertFalse(body.has("clone_url"));
        assertFalse(body.has("head_sha"));
        assertFalse(body.has("json_schema"));
    }

    /** The credential, the MCP key and the clone token reach the launcher and nothing else, on the loud path too. */
    @Test
    void secretsNeverReachALogLine() throws Exception {
        ScriptedHttpClient client = ScriptedHttpClient.answering(502, errorBody("script_exit"));

        assertThrows(TessaryException.class, () -> sandbox(client).run(fullRequest()));

        assertFalse(appender.list.isEmpty(), "the refusal is logged");
        for (ILoggingEvent event : appender.list) {
            String rendered = event.getFormattedMessage() + " " + event.getKeyValuePairs() + " "
                    + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage());
            for (String secret : List.of("sk-ant-secretkey", MCP_TOKEN, "ghs_clonetoken", API_KEY)) {
                assertFalse(rendered.contains(secret), secret + " leaked into: " + rendered);
            }
        }
    }

    /** A lane with no launcher of its own rides the observer's, URL and key both. */
    @Test
    void fallsBackToTheObserversLauncher() throws Exception {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, envelope(Map.of("result", "ok")));
        AgentRunProperties own = props("");
        own.setLauncherApiKey("");

        sandbox(own, observer("http://observer-launcher", "observer-k"), client).run(fullRequest());

        assertEquals(
                "http://observer-launcher/authoring", client.lastRequest().uri().toString());
        assertEquals(
                List.of("Bearer observer-k"), client.lastRequest().headers().allValues("Authorization"));
    }

    /** No launcher anywhere is a deployment mistake to see, not a request to make. */
    @Test
    void noLauncherAtAllIsMisconfiguredBeforeAnyCall() {
        ScriptedHttpClient client = new ScriptedHttpClient();

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> sandbox(props(""), observer("", ""), client).run(fullRequest()));

        assertEquals(AgentRunError.LAUNCHER_MISCONFIGURED, ex.error());
        assertEquals(List.of(), client.sent());
        verifyNoInteractions(usage);
    }

    // ---- the answer ---------------------------------------------------------------------------------

    /** The validated object is the answer; the raw text rides beside it; the ledger gets the run's spend. */
    @Test
    void aCompletedRunReturnsTheStructuredOutputAndBooksItsUsage() throws Exception {
        String body = envelope(Map.of(
                "structured_output",
                Map.of("builder", "x => x.error"),
                "result",
                "```json\n{\"builder\":\"x => x.error\"}\n```",
                "num_turns",
                7,
                "usage",
                Map.of(
                        "input_tokens",
                        100,
                        "output_tokens",
                        20,
                        "cache_read_input_tokens",
                        5,
                        "cache_creation_input_tokens",
                        3),
                "total_cost_usd",
                0.5));

        AgentRunSandbox.Result result =
                sandbox(ScriptedHttpClient.answering(200, body)).run(fullRequest());

        assertEquals(MAPPER.readTree("{\"builder\":\"x => x.error\"}"), result.structuredOutput());
        assertEquals("```json\n{\"builder\":\"x => x.error\"}\n```", result.resultText());
        assertEquals(7, result.turns());
        verify(usage)
                .recordSandboxRun(
                        "proj",
                        "authoring",
                        "claude-sonnet-5-5",
                        "anthropic/claude-sonnet-5-5",
                        false,
                        100L,
                        20L,
                        5L,
                        3L,
                        BigDecimal.valueOf(0.5),
                        SUBJECT);
    }

    /** A prose answer (no schema asked) is still an answer: text present, no structured half. */
    @Test
    void aProseAnswerIsReturnedAsTextAlone() throws Exception {
        String body = envelope(Map.of("result", "The call site retries three times.", "num_turns", 2));

        AgentRunSandbox.Result result =
                sandbox(ScriptedHttpClient.answering(200, body)).run(request(null, null, null));

        assertNull(result.structuredOutput());
        assertEquals("The call site retries three times.", result.resultText());
        assertEquals(2, result.turns());
    }

    /** A 2xx with nothing in it is not a run that answered: no raw, a blank result, or a body that is not JSON. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"kind\":\"timeout\"}", "{\"raw\":\"{\\\"result\\\":\\\"\\\"}\"}", "<html>proxy</html>"})
    void anEmptyAnswerIsBadOutput(String body) {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> sandbox(ScriptedHttpClient.answering(200, body)).run(fullRequest()));

        assertEquals(AgentRunError.BAD_OUTPUT, ex.error());
    }

    // ---- the failures -------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "401 | ''                          | LAUNCHER_MISCONFIGURED",
                "403 | ''                          | LAUNCHER_MISCONFIGURED",
                "404 | {\"kind\":\"orchestration\"}   | LAUNCHER_MISCONFIGURED",
                "502 | {\"kind\":\"timeout\"}         | TIMED_OUT",
                "502 | {\"kind\":\"script_exit\"}     | RUN_FAILED",
                "502 | {\"kind\":\"bad_request\"}     | RUN_FAILED",
                "502 | {\"kind\":\"bad_output\"}      | BAD_OUTPUT",
                "502 | {\"kind\":\"orchestration\"}   | LAUNCHER_UNAVAILABLE",
                "502 | ''                          | LAUNCHER_UNAVAILABLE",
                "502 | <html>Bad Gateway</html>    | LAUNCHER_UNAVAILABLE",
                "502 | {\"kind\":\"something_new\"}   | LAUNCHER_UNAVAILABLE",
                "500 | {\"kind\":\"script_exit\"}     | LAUNCHER_UNAVAILABLE",
                "503 | {\"kind\":\"timeout\"}         | LAUNCHER_UNAVAILABLE",
                "400 | ''                          | RUN_FAILED",
                "422 | {}                          | RUN_FAILED"
            })
    void classifySplitsTheRunFromTheLauncher(int status, String body, AgentRunError expected) {
        assertEquals(expected, E2bAgentRunSandbox.classify(status, body));
    }

    /** The classification reaches the caller as the thrown code, with the launcher's own diagnosis in it. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "401 | unauthorized        | LAUNCHER_MISCONFIGURED | The agent launcher refused the run: status 401 from http://launcher, the shared secret does not match; TESSARY_AGENTRUN_LAUNCHER_API_KEY (or the observer's) must equal the launcher's SANDBOX_API_KEY",
                "404 | ''                  | LAUNCHER_MISCONFIGURED | The agent launcher refused the run: status 404 from http://launcher, no /authoring route; the launcher image is older than this backend",
                "502 | orchestration       | LAUNCHER_UNAVAILABLE   | The agent launcher is not answering: status 502 from http://launcher, kind=orchestration detail=the launcher's diagnosis",
                "502 | timeout             | TIMED_OUT              | The agent run hit its 900000ms wall clock",
                "502 | script_exit         | RUN_FAILED             | The agent run failed: kind=script_exit detail=the launcher's diagnosis",
                "502 | bad_output          | BAD_OUTPUT             | The agent returned nothing usable: kind=bad_output detail=the launcher's diagnosis"
            })
    void aRejectedRunThrowsItsClassification(int status, String kind, AgentRunError expected, String message)
            throws Exception {
        String body = kind.isEmpty() ? "" : "unauthorized".equals(kind) ? kind : errorBody(kind);

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> sandbox(ScriptedHttpClient.answering(status, body)).run(fullRequest()));

        assertEquals(expected, ex.error());
        assertEquals(message, ex.getMessage());
    }

    /** A launcher that never answered is unreachable; one that answered the connection and then stalled timed out. */
    @Test
    void transportFailuresSplitIntoUnreachableAndTimedOut() {
        TessaryException refused = assertThrows(
                TessaryException.class,
                () -> sandbox(ScriptedHttpClient.failingWith(new ConnectException("refused")))
                        .run(fullRequest()));
        assertEquals(AgentRunError.LAUNCHER_UNAVAILABLE, refused.error());
        assertEquals(
                "The agent launcher is not answering: unreachable at http://launcher (ConnectException)",
                refused.getMessage());

        TessaryException stalled = assertThrows(
                TessaryException.class,
                () -> sandbox(ScriptedHttpClient.failingWith(new HttpTimeoutException("request timed out")))
                        .run(fullRequest()));
        assertEquals(AgentRunError.TIMED_OUT, stalled.error());
        verifyNoInteractions(usage);
    }

    /** Cancellation propagates as the thread's interrupt, never folded into a run outcome. */
    @Test
    void anInterruptedRunKeepsTheInterrupt() {
        try {
            TessaryException ex = assertThrows(
                    TessaryException.class,
                    () -> sandbox(ScriptedHttpClient.failingWith(new InterruptedException()))
                            .run(fullRequest()));
            assertEquals(CommonError.INTERRUPTED, ex.error());
            assertTrue(Thread.interrupted(), "the interrupt is restored for the caller to see");
        } finally {
            Thread.interrupted();
        }
    }

    /** A run that failed after spending tokens is booked exactly as a completed one; $0 would be a lie. */
    @Test
    void aFailedRunStillBooksWhatItSpent() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "kind", "script_exit",
                "detail", "exit 1",
                "usage", Map.of("input_tokens", 120, "output_tokens", 40)));

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> sandbox(ScriptedHttpClient.answering(502, body)).run(fullRequest()));

        assertEquals(AgentRunError.RUN_FAILED, ex.error());
        verify(usage)
                .recordSandboxRun(
                        "proj",
                        "authoring",
                        "claude-sonnet-5-5",
                        "anthropic/claude-sonnet-5-5",
                        false,
                        120L,
                        40L,
                        0L,
                        0L,
                        null,
                        SUBJECT);
    }

    /** One real round trip over a loopback socket: the path, the bearer and the body the launcher actually sees. */
    @Test
    void aRealRoundTripReachesTheAuthoringRoute() throws Exception {
        try (LoopbackHttpStub launcher = LoopbackHttpStub.answering(200, envelope(Map.of("result", "ok")))) {
            E2bAgentRunSandbox sandbox = sandbox(
                    props(launcher.baseUrl()),
                    observer("", ""),
                    HttpClient.newBuilder().build());

            AgentRunSandbox.Result result = sandbox.run(request(null, null, null));

            assertEquals("ok", result.resultText());
            LoopbackHttpStub.Request seen = launcher.lastRequest();
            assertEquals("/authoring", seen.path());
            assertEquals("Bearer " + API_KEY, seen.headers().get("authorization"));
            assertEquals(
                    "Write one for timeouts.",
                    MAPPER.readTree(seen.body()).path("prompt").asText());
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.ObserverProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.RcaProperties.Agentic;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link E2bRcaSandbox}'s envelope handling through the launcher-POST seam. Fail closed: an unusable envelope (no
 * result, non-2xx, transport failure) throws so the report stamps {@code failed}; the body carries the secrets and
 * dossier the script expects; a repoless project sends no clone at all.
 */
class E2bRcaSandboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RcaProperties props() {
        RcaProperties p = new RcaProperties();
        p.getAgentic().setLauncherUrl("http://launcher");
        p.getAgentic().setLauncherApiKey("k");
        return p;
    }

    /** A project with no RCA lane pinned falls back to the observer's model. */
    private static ProjectModelSettings noLaneSetting() {
        ProjectModelSettings s = mock(ProjectModelSettings.class);
        when(s.resolveAgenticModel(any(), any())).thenReturn(Optional.empty());
        return s;
    }

    /** Every run injects an org credential; no test asserts its contents, so one shared shape is enough. */
    private static AgenticCredentialResolver credentials() {
        AgenticCredentialResolver c = mock(AgenticCredentialResolver.class);
        when(c.resolve(any(), any()))
                .thenReturn(new AgenticCredentialResolver.Credential(
                        ModelProvider.BEDROCK, null, null, null, "us-east-1", "ak", "sk"));
        return c;
    }

    private static RcaSandbox.SandboxRequest request() {
        return new RcaSandbox.SandboxRequest(
                "proj",
                "g1",
                "https://x-access-token:tok@host/r.git",
                "sha",
                Map.of("movement.md", "the movement"),
                "investigate",
                "{}",
                "https://api.example/mcp",
                "tsy_a_secret",
                "report-1");
    }

    /** A claude result envelope whose {@code result} is the agent's final JSON message. */
    private static String envelope(String resultText) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "raw",
                MAPPER.writeValueAsString(Map.of(
                        "result",
                        resultText,
                        "total_cost_usd",
                        0.42,
                        "usage",
                        Map.of("input_tokens", 10, "output_tokens", 5))),
                "turns",
                java.util.List.of(),
                "startMs",
                0));
    }

    /** Plus the schema-validated object agent-stream.js emits when the reply satisfied the schema. */
    private static String envelopeWithStructuredOutput(String resultText, String structuredJson) throws Exception {
        java.util.Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("result", resultText);
        inner.put("structured_output", MAPPER.readTree(structuredJson));
        inner.put("total_cost_usd", 0.42);
        inner.put("usage", Map.of("input_tokens", 10, "output_tokens", 5));
        return MAPPER.writeValueAsString(
                Map.of("raw", MAPPER.writeValueAsString(inner), "turns", java.util.List.of(), "startMs", 0));
    }

    /**
     * {@code structured_output} wins over {@code result}: it is already schema-validated, while {@code result} is raw
     * reply text that may carry a fence or prose. Reading {@code result} first discarded a finished 4m48s, $0.80
     * investigation.
     */
    @Test
    void prefersTheValidatedStructuredOutputOverTheRawReplyText() throws Exception {
        E2bRcaSandbox sandbox = stubbed(bodyJson -> envelopeWithStructuredOutput(
                "Here you go:\n```json\n{\"verdict\":\"model_change\"}\n```",
                "{\"verdict\":\"model_change\",\"summary\":\"s\"}"));

        RcaSandbox.SandboxRun run = sandbox.run(request());

        var parsed = MAPPER.readTree(run.resultText());
        assertEquals("model_change", parsed.path("verdict").asText());
        assertEquals("s", parsed.path("summary").asText());
    }

    @Test
    void returnsAgentResultTextAndPostsFullBody() throws Exception {
        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox = stubbed(bodyJson -> {
            posted.append(bodyJson);
            return envelope("{\"verdict\":\"behavior_change\"}");
        });

        RcaSandbox.SandboxRun run = sandbox.run(request());

        assertEquals("{\"verdict\":\"behavior_change\"}", run.resultText());
        var body = MAPPER.readTree(posted.toString());
        assertEquals("sha", body.path("head_sha").asText());
        assertEquals("the movement", body.path("files").path("movement.md").asText());
        assertEquals("https://api.example/mcp", body.path("mcp").path("url").asText());
        assertEquals("tsy_a_secret", body.path("mcp").path("token").asText());
        assertTrue(body.path("clone_url").asText().contains("x-access-token"));
        // {@code provider} is always sent: no lane selection means BEDROCK, and its resolved credential rides along,
        // never logged.
        assertEquals("BEDROCK", body.path("provider").asText());
        assertFalse(body.path("credential").isMissingNode(), "credential must always be present");
    }

    @Test
    void projectRcaLaneOverridesTheObserverModel() throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(new ProjectModelSettings.ResolvedAgenticModel(
                        ai.tessary.llm.ModelProvider.BEDROCK,
                        "global.anthropic.claude-sonnet-4-6",
                        "global.anthropic.claude-sonnet-4-6")));

        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox = stubbed(settings, mock(LlmUsageAccountant.class), bodyJson -> {
            posted.append(bodyJson);
            return envelope("{}");
        });

        sandbox.run(request());

        // The lane wins over ObserverProperties' default.
        var body = MAPPER.readTree(posted.toString());
        assertEquals("global.anthropic.claude-sonnet-4-6", body.path("model").asText());
        // An explicit lane names its provider, so the launcher need not parse the model id.
        assertEquals("BEDROCK", body.path("provider").asText());
    }

    /**
     * A non-Bedrock provider sends a bare model id plus an explicit {@code provider}, since there is no Bedrock
     * syntax to parse.
     */
    @Test
    void projectRcaLaneOnANonBedrockProvider_sendsTheBareModelIdAndProviderField() throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(new ProjectModelSettings.ResolvedAgenticModel(
                        ai.tessary.llm.ModelProvider.GEMINI, "gemini-2.5-pro", "gemini-2.5-pro")));

        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox = stubbed(settings, mock(LlmUsageAccountant.class), bodyJson -> {
            posted.append(bodyJson);
            return envelope("{}");
        });

        sandbox.run(request());

        var body = MAPPER.readTree(posted.toString());
        assertEquals("gemini-2.5-pro", body.path("model").asText());
        assertEquals("GEMINI", body.path("provider").asText());
    }

    /** No git integration: the clone fields are omitted, not empty, because the script branches on their presence. */
    @Test
    void aRepolessRequestOmitsTheCloneFields() throws Exception {
        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox = stubbed(bodyJson -> {
            posted.append(bodyJson);
            return envelope("{}");
        });

        sandbox.run(new RcaSandbox.SandboxRequest(
                "proj", "g1", null, null, Map.of(), "p", "{}", "https://api.example/mcp", "tsy_a_secret", "report-1"));

        var body = MAPPER.readTree(posted.toString());
        assertTrue(body.path("clone_url").isMissingNode());
        assertTrue(body.path("head_sha").isMissingNode());
        // The evidence door is the run's only substrate, so it is never optional.
        assertEquals("https://api.example/mcp", body.path("mcp").path("url").asText());
    }

    @Test
    void blankResultFailsClosed() {
        E2bRcaSandbox sandbox = stubbed(bodyJson -> "{\"raw\":\"{}\",\"turns\":[],\"startMs\":0}");

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }

    /**
     * One ledger entry per run against the RCA lane and its report, with the envelope's tokens and cost; never
     * platform-funded.
     */
    @Test
    void aFinishedRunBooksItsTokensAndCostToTheReport() throws Exception {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        E2bRcaSandbox sandbox =
                stubbed(noLaneSetting(), usage, bodyJson -> envelope("{\"verdict\":\"behavior_change\"}"));

        sandbox.run(request());

        verifyBooked(usage, 10, 5, new BigDecimal("0.42"));
    }

    /**
     * A failed run still spent tokens, carried in the launcher's error body; they are booked before the failure
     * propagates.
     */
    @Test
    void aRunTheLauncherFailedStillBooksWhatItSpent() throws Exception {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        try (ServerSocket launcher = launcherAnswering(
                502,
                "{\"error\":\"sandbox orchestration failed\",\"kind\":\"agent_failed\","
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}")) {
            RcaProperties p = launcherAt(launcher.getLocalPort());
            E2bRcaSandbox sandbox = sandbox(p, usage);

            TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
            assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        }

        verifyBooked(usage, 7, 3, null);
    }

    /** A one-shot loopback launcher; forbidden-apis bans {@code com.sun.net.httpserver}, so it is a bare socket. */
    private static ServerSocket launcherAnswering(int status, String body) throws IOException {
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread responder = new Thread(
                () -> {
                    try (Socket client = socket.accept()) {
                        InputStream in = client.getInputStream();
                        int contentLength = 0;
                        for (String line : readHead(in).split("\r\n")) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                contentLength = Integer.parseInt(line.substring("content-length:".length())
                                        .trim());
                            }
                        }
                        in.readNBytes(contentLength);
                        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 " + status + " Stub\r\n"
                                        + "Content-Type: application/json\r\n"
                                        + "Content-Length: " + payload.length + "\r\n"
                                        + "Connection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(payload);
                        out.flush();
                    } catch (IOException e) {
                    }
                },
                "stub-rca-launcher");
        responder.setDaemon(true);
        responder.start();
        return socket;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            head.write(c);
            byte[] seen = head.toByteArray();
            int n = seen.length;
            if (n >= 4 && seen[n - 4] == '\r' && seen[n - 3] == '\n' && seen[n - 2] == '\r' && seen[n - 1] == '\n') {
                break;
            }
        }
        return head.toString(StandardCharsets.UTF_8);
    }

    /**
     * Catches a stopped launcher reported as an LLM failure: a refused connection is {@code LAUNCHER_UNREACHABLE},
     * naming host and exception class.
     */
    @Test
    void aLauncherThatIsNotListeningIsReportedUnreachable() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        RcaProperties p = launcherAt(port);

        TessaryException ex =
                assertThrows(TessaryException.class, () -> sandbox(p).run(request()));

        assertEquals(RcaError.LAUNCHER_UNREACHABLE, ex.error());
        assertTrue(ex.getMessage().contains("http://127.0.0.1:" + port + " (ConnectException)"), ex.getMessage());
    }

    /**
     * Catches a dropped launcher diagnosis, parts joined wrongly when leading ones are absent, and a non-JSON body (a
     * proxy's 502 page) failing the diagnosis.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "'{\"kind\":\"agent_failed\",\"detail\":\"exit 1\",\"sandbox_id\":\"sb-9\",\"elapsed_ms\":1200}'"
                        + " | agentic RCA launcher HTTP 502 (kind=agent_failed detail=exit 1 sandbox=sb-9 elapsed_ms=1200)",
                "'{\"detail\":\"exit 1\"}' | agentic RCA launcher HTTP 502 (detail=exit 1)",
                "'{\"sandbox_id\":\"sb-9\"}' | agentic RCA launcher HTTP 502 (sandbox=sb-9)",
                "'{\"elapsed_ms\":5}' | agentic RCA launcher HTTP 502 (elapsed_ms=5)",
                "'<html>bad gateway</html>' | agentic RCA launcher HTTP 502"
            })
    void aRejectedRunCarriesTheLaunchersDiagnosis(String body, String expected) throws Exception {
        try (ServerSocket launcher = launcherAnswering(502, body)) {
            RcaProperties p = launcherAt(launcher.getLocalPort());

            TessaryException ex =
                    assertThrows(TessaryException.class, () -> sandbox(p).run(request()));

            assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
            assertTrue(ex.getMessage().endsWith(expected), ex.getMessage());
        }
    }

    /** Catches a 2xx body not being where the result is read from. */
    @Test
    void anAcceptedRunReadsItsResultFromTheLaunchersBody() throws Exception {
        try (ServerSocket launcher = launcherAnswering(200, envelope("{\"summary\":\"from the launcher\"}"))) {
            RcaProperties p = launcherAt(launcher.getLocalPort());

            assertEquals(
                    "{\"summary\":\"from the launcher\"}",
                    sandbox(p).run(request()).resultText());
        }
    }

    /**
     * Catches a swallowed interrupt: the thread stays interrupted so its executor can stop, and the run fails as
     * INTERRUPTED.
     */
    @Test
    void anInterruptedWaitFailsTheRunAndKeepsTheInterrupt() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RcaProperties p = launcherAt(silent.getLocalPort());
            E2bRcaSandbox sandbox = sandbox(p);

            Thread.currentThread().interrupt();
            TessaryException ex;
            boolean stillInterrupted;
            try {
                ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
            } finally {
                stillInterrupted = Thread.interrupted();
            }

            assertEquals(CommonError.INTERRUPTED, ex.error());
            assertTrue(stillInterrupted);
        }
    }

    /**
     * Catches a non-JSON envelope escaping as a raw parse exception: it fails as UPSTREAM_FAILED with the cause
     * attached.
     */
    @Test
    void anUnreadableEnvelopeFailsClosedWithItsCause() {
        E2bRcaSandbox sandbox = stubbed(bodyJson -> "<html>not an envelope</html>");

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));

        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        assertTrue(ex.getCause() instanceof IOException, String.valueOf(ex.getCause()));
    }

    @Test
    void unconfiguredLauncherFailsClosed() {
        RcaProperties bare = new RcaProperties(); // launcherUrl unset
        E2bRcaSandbox sandbox = sandbox(bare);

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }

    /** The launcher's answer to one posted body. */
    @FunctionalInterface
    private interface Launcher {
        String answer(String bodyJson) throws Exception;
    }

    private static E2bRcaSandbox stubbed(Launcher launcher) {
        return stubbed(noLaneSetting(), mock(LlmUsageAccountant.class), launcher);
    }

    private static E2bRcaSandbox stubbed(ProjectModelSettings settings, LlmUsageAccountant usage, Launcher launcher) {
        return new E2bRcaSandbox(
                props(), new ObserverProperties(), settings, credentials(), usage, OpenTelemetry.noop(), MAPPER) {
            @Override
            String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                try {
                    return launcher.answer(bodyJson);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static E2bRcaSandbox sandbox(RcaProperties p) {
        return sandbox(p, mock(LlmUsageAccountant.class));
    }

    private static E2bRcaSandbox sandbox(RcaProperties p, LlmUsageAccountant usage) {
        return new E2bRcaSandbox(
                p, new ObserverProperties(), noLaneSetting(), credentials(), usage, OpenTelemetry.noop(), MAPPER);
    }

    private static RcaProperties launcherAt(int port) {
        RcaProperties p = props();
        p.getAgentic().setLauncherUrl("http://127.0.0.1:" + port);
        return p;
    }

    private static void verifyBooked(LlmUsageAccountant usage, long in, long out, @Nullable BigDecimal cost) {
        verify(usage)
                .recordSandboxRun(
                        eq("proj"),
                        eq("rca"),
                        any(),
                        any(),
                        eq(false),
                        eq(in),
                        eq(out),
                        eq(0L),
                        eq(0L),
                        cost == null ? isNull() : eq(cost),
                        eq(new LlmUsageAccountant.Subject("rca_report", "report-1")));
    }
}

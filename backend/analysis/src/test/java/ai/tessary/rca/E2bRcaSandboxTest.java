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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The launcher-envelope handling in {@link E2bRcaSandbox}, exercised by overriding the
 * launcher-POST seam (same pattern as {@code E2bAgenticSynthesisSandboxTest}) — no live launcher or
 * microVM needed. Pins the fail-closed contract: an unusable envelope (no result text, non-2xx,
 * transport failure) always throws so the report stamps {@code failed}, never a silent empty
 * verdict; that the request body carries the secrets + dossier the sandbox script expects; and that a
 * project with no repository sends no clone at all rather than an empty one.
 */
class E2bRcaSandboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RcaProperties props() {
        RcaProperties p = new RcaProperties();
        p.getAgentic().setLauncherUrl("http://launcher");
        p.getAgentic().setLauncherApiKey("k");
        return p;
    }

    /** A project that has not pinned the RCA lane — the sandbox falls back to the observer's model. */
    private static ProjectModelSettings noLaneSetting() {
        ProjectModelSettings s = mock(ProjectModelSettings.class);
        when(s.resolveAgenticModel(any(), any())).thenReturn(Optional.empty());
        return s;
    }

    /** Every run resolves + injects an org credential now — a lenient stub covering
     *  whichever provider each test's lane resolution (or the BEDROCK default, for an unresolved
     *  lane — see E2bRcaSandbox#providerFor) actually asks for. No test here asserts the
     *  credential's own contents (only `provider`, a plain non-secret field, and the rest of the
     *  request body), so one shared shape is enough. */
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

    /** The same envelope, plus the schema-validated object agent-stream.js emits beside the raw text
     *  when the reply satisfied the response schema. */
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
     * `structured_output` wins over `result`. It is the object the sandbox ALREADY validated against the
     * response schema (agent-stream.js will not exit 0 without it), where `result` is the same answer as
     * the model's raw reply text and may carry a fence or a sentence of prose. Reading `result` first
     * discarded a complete 4m48s / $0.80 investigation whose validated object sat unread beside it.
     */
    @Test
    void prefersTheValidatedStructuredOutputOverTheRawReplyText() throws Exception {
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        noLaneSetting(),
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        try {
                            return envelopeWithStructuredOutput(
                                    "Here you go:\n```json\n{\"verdict\":\"model_change\"}\n```",
                                    "{\"verdict\":\"model_change\",\"summary\":\"s\"}");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        RcaSandbox.SandboxRun run = sandbox.run(request());

        var parsed = MAPPER.readTree(run.resultText());
        assertEquals("model_change", parsed.path("verdict").asText());
        assertEquals("s", parsed.path("summary").asText());
    }

    @Test
    void returnsAgentResultTextAndPostsFullBody() throws Exception {
        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        noLaneSetting(),
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        posted.append(bodyJson);
                        try {
                            return envelope("{\"verdict\":\"behavior_change\"}");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        RcaSandbox.SandboxRun run = sandbox.run(request());

        assertEquals("{\"verdict\":\"behavior_change\"}", run.resultText());
        var body = MAPPER.readTree(posted.toString());
        assertEquals("sha", body.path("head_sha").asText());
        assertEquals("the movement", body.path("files").path("movement.md").asText());
        assertEquals("https://api.example/mcp", body.path("mcp").path("url").asText());
        assertEquals("tsy_a_secret", body.path("mcp").path("token").asText());
        assertTrue(body.path("clone_url").asText().contains("x-access-token"));
        // `provider` is ALWAYS sent now (never omitted) — there is no deployment-wide
        // default left for the launcher to fall through to. No explicit lane selection defaults to
        // BEDROCK (see E2bRcaSandbox#providerFor's javadoc for why that specific default), and the
        // credential resolved for it rides on the request too (never logged — see `credentials()`).
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
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        settings,
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        posted.append(bodyJson);
                        try {
                            return envelope("{}");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        sandbox.run(request());

        // The lane wins; an unset lane (every other test here) still gets ObserverProperties' default.
        var body = MAPPER.readTree(posted.toString());
        assertEquals("global.anthropic.claude-sonnet-4-6", body.path("model").asText());
        // An explicit lane selection also names its provider, so the launcher's providerConfig()
        // knows which block to build without having to parse the model id's shape.
        assertEquals("BEDROCK", body.path("provider").asText());
    }

    /**
     * A project pointed at one of the four new non-Bedrock providers sends a bare model id
     * (not a Bedrock inference-profile string) plus an explicit {@code provider} field — the launcher
     * has no Bedrock-syntax hint to parse for these, so the field is how it knows what to build.
     */
    @Test
    void projectRcaLaneOnANonBedrockProvider_sendsTheBareModelIdAndProviderField() throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(new ProjectModelSettings.ResolvedAgenticModel(
                        ai.tessary.llm.ModelProvider.GEMINI, "gemini-2.5-pro", "gemini-2.5-pro")));

        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        settings,
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        posted.append(bodyJson);
                        try {
                            return envelope("{}");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        sandbox.run(request());

        var body = MAPPER.readTree(posted.toString());
        assertEquals("gemini-2.5-pro", body.path("model").asText());
        assertEquals("GEMINI", body.path("provider").asText());
    }

    /** A project with no git integration: the clone fields are OMITTED, not sent empty, because the
     *  sandbox script branches on their presence to decide whether there is a ./repo/ at all. */
    @Test
    void aRepolessRequestOmitsTheCloneFields() throws Exception {
        StringBuilder posted = new StringBuilder();
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        noLaneSetting(),
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        posted.append(bodyJson);
                        try {
                            return envelope("{}");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        sandbox.run(new RcaSandbox.SandboxRequest(
                "proj", "g1", null, null, Map.of(), "p", "{}", "https://api.example/mcp", "tsy_a_secret", "report-1"));

        var body = MAPPER.readTree(posted.toString());
        assertTrue(body.path("clone_url").isMissingNode());
        assertTrue(body.path("head_sha").isMissingNode());
        // The evidence door is not optional the way the repo is — it is the run's only substrate.
        assertEquals("https://api.example/mcp", body.path("mcp").path("url").asText());
    }

    @Test
    void blankResultFailsClosed() {
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        noLaneSetting(),
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        return "{\"raw\":\"{}\",\"turns\":[],\"startMs\":0}";
                    }
                };

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }

    /**
     * The run is one ledger entry against the RCA lane and the report it investigated. Without it the
     * org's spend and metering silently leave RCA runs out. The tokens and cost are the envelope's own
     * (input 10, output 5, $0.42); the run carries the org's credential, so it is never platform-funded.
     */
    @Test
    void aFinishedRunBooksItsTokensAndCostToTheReport() throws Exception {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        noLaneSetting(),
                        credentials(),
                        usage,
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        try {
                            return envelope("{\"verdict\":\"behavior_change\"}");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };

        sandbox.run(request());

        verify(usage)
                .recordSandboxRun(
                        eq("proj"),
                        eq("rca"),
                        any(),
                        any(),
                        eq(false),
                        eq(10L),
                        eq(5L),
                        eq(0L),
                        eq(0L),
                        eq(new BigDecimal("0.42")),
                        eq(new LlmUsageAccountant.Subject("rca_report", "report-1")));
    }

    /**
     * A run the launcher failed still spent tokens before it did, and the launcher's error body carries
     * them. They are booked before the failure propagates, or a failing RCA costs the org money the
     * ledger never shows. Goes through the real launcher POST against a loopback stub.
     */
    @Test
    void aRunTheLauncherFailedStillBooksWhatItSpent() throws Exception {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        try (ServerSocket launcher = launcherAnswering(
                502,
                "{\"error\":\"sandbox orchestration failed\",\"kind\":\"agent_failed\","
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}")) {
            RcaProperties p = props();
            p.getAgentic().setLauncherUrl("http://127.0.0.1:" + launcher.getLocalPort());
            E2bRcaSandbox sandbox = new E2bRcaSandbox(
                    p, new ObserverProperties(), noLaneSetting(), credentials(), usage, OpenTelemetry.noop(), MAPPER);

            TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
            assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        }

        verify(usage)
                .recordSandboxRun(
                        eq("proj"),
                        eq("rca"),
                        any(),
                        any(),
                        eq(false),
                        eq(7L),
                        eq(3L),
                        eq(0L),
                        eq(0L),
                        isNull(),
                        eq(new LlmUsageAccountant.Subject("rca_report", "report-1")));
    }

    /**
     * A one-shot loopback launcher: reads one request and answers it with {@code status} and {@code body}.
     * forbidden-apis bans {@code com.sun.net.httpserver}, so this is a bare socket.
     */
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
                        // The socket closed first; the test's own assertions report what went wrong.
                    }
                },
                "stub-rca-launcher");
        responder.setDaemon(true);
        responder.start();
        return socket;
    }

    /** Read up to and including the blank line ending the request head. */
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
     * Catches a launcher that is not running being reported as an LLM failure: a refused connection is
     * {@code LAUNCHER_UNREACHABLE} and names the host and the exception class, so the UI and the log point at
     * the infrastructure rather than the model.
     */
    @Test
    void aLauncherThatIsNotListeningIsReportedUnreachable() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        RcaProperties p = props();
        p.getAgentic().setLauncherUrl("http://127.0.0.1:" + port);

        TessaryException ex =
                assertThrows(TessaryException.class, () -> sandbox(p).run(request()));

        assertEquals(RcaError.LAUNCHER_UNREACHABLE, ex.error());
        assertTrue(ex.getMessage().contains("http://127.0.0.1:" + port + " (ConnectException)"), ex.getMessage());
    }

    /**
     * Catches a rejected run whose launcher diagnosis is dropped (only the status code survives), a diagnosis
     * that joins its parts wrongly when the leading ones are absent, and a body that is not JSON (a proxy's own
     * 502 page) failing the diagnosis instead of leaving the bare status.
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
            RcaProperties p = props();
            p.getAgentic().setLauncherUrl("http://127.0.0.1:" + launcher.getLocalPort());

            TessaryException ex =
                    assertThrows(TessaryException.class, () -> sandbox(p).run(request()));

            assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
            assertTrue(ex.getMessage().endsWith(expected), ex.getMessage());
        }
    }

    /** Catches a 2xx launcher answer not being what the run reads its result from. */
    @Test
    void anAcceptedRunReadsItsResultFromTheLaunchersBody() throws Exception {
        try (ServerSocket launcher = launcherAnswering(200, envelope("{\"summary\":\"from the launcher\"}"))) {
            RcaProperties p = props();
            p.getAgentic().setLauncherUrl("http://127.0.0.1:" + launcher.getLocalPort());

            assertEquals(
                    "{\"summary\":\"from the launcher\"}",
                    sandbox(p).run(request()).resultText());
        }
    }

    /**
     * Catches an interrupted wait on the launcher swallowing the interrupt: the worker thread must come back
     * still marked interrupted so its executor can shut down, and the run fails as INTERRUPTED rather than as
     * an upstream fault.
     */
    @Test
    void anInterruptedWaitFailsTheRunAndKeepsTheInterrupt() throws Exception {
        // Accepts connections into its backlog and never answers them.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            RcaProperties p = props();
            p.getAgentic().setLauncherUrl("http://127.0.0.1:" + silent.getLocalPort());
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
     * Catches a launcher envelope that is not JSON escaping as a raw parse exception: it must fail the run as
     * UPSTREAM_FAILED with the parse failure as its cause, so the report stamps failed.
     */
    @Test
    void anUnreadableEnvelopeFailsClosedWithItsCause() {
        E2bRcaSandbox sandbox =
                new E2bRcaSandbox(
                        props(),
                        new ObserverProperties(),
                        noLaneSetting(),
                        credentials(),
                        mock(LlmUsageAccountant.class),
                        OpenTelemetry.noop(),
                        MAPPER) {
                    @Override
                    String postLauncher(String bodyJson, Agentic cfg, String projectId, String reportId) {
                        return "<html>not an envelope</html>";
                    }
                };

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));

        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        assertTrue(ex.getCause() instanceof IOException, String.valueOf(ex.getCause()));
    }

    private static E2bRcaSandbox sandbox(RcaProperties p) {
        return new E2bRcaSandbox(
                p,
                new ObserverProperties(),
                noLaneSetting(),
                credentials(),
                mock(LlmUsageAccountant.class),
                OpenTelemetry.noop(),
                MAPPER);
    }

    @Test
    void unconfiguredLauncherFailsClosed() {
        RcaProperties bare = new RcaProperties(); // launcherUrl unset
        E2bRcaSandbox sandbox = new E2bRcaSandbox(
                bare,
                new ObserverProperties(),
                noLaneSetting(),
                credentials(),
                mock(LlmUsageAccountant.class),
                OpenTelemetry.noop(),
                MAPPER);

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }
}

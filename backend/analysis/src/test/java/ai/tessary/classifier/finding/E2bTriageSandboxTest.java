// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The launcher-envelope handling in {@link E2bTriageSandbox} — same seam pattern as
 * {@code E2bRcaSandboxTest}, but stubbed one layer deeper: overriding {@code send} substitutes only
 * the socket round-trip postLauncher makes, so the actual status/kind classification and
 * usage-booking this class exists to pin stay real. Pins decision 3 of the triage-fixes plan: a
 * run failure spends the finding's attempt ({@code TRIAGE_RUN_INCOMPLETE}), and only an actual
 * launcher fault trips the breaker ({@code TRIAGE_LAUNCHER_UNAVAILABLE} /
 * {@code TRIAGE_LAUNCHER_MISCONFIGURED}).
 */
class E2bTriageSandboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObserverProperties props() {
        ObserverProperties p = new ObserverProperties();
        p.getAgentic().setLauncherUrl("http://launcher");
        p.getAgentic().setLauncherApiKey("k");
        return p;
    }

    /** A project that has not pinned the TRIAGE lane — the sandbox falls back to the observer's model. */
    private static ProjectModelSettings noLaneSetting() {
        ProjectModelSettings s = mock(ProjectModelSettings.class);
        when(s.resolveAgenticModel(any(), any())).thenReturn(Optional.empty());
        return s;
    }

    private static AgenticCredentialResolver credentials() {
        AgenticCredentialResolver c = mock(AgenticCredentialResolver.class);
        when(c.resolve(any(), any()))
                .thenReturn(new AgenticCredentialResolver.Credential(
                        ModelProvider.BEDROCK, null, null, null, "us-east-1", "ak", "sk"));
        return c;
    }

    private static TriageSandbox.SandboxRequest request() {
        return new TriageSandbox.SandboxRequest(
                "proj",
                "f1",
                Map.of("finding.md", "the finding"),
                "rule on it",
                "{}",
                "https://api.example/mcp",
                "tsy_a_secret",
                "system prompt");
    }

    /** A launcher error body in server.js's {@code buildErrorBody} shape. */
    private static String errorBody(String kind, String detail) throws Exception {
        return MAPPER.writeValueAsString(Map.of("kind", kind, "detail", detail));
    }

    private static String errorBodyWithUsage(String kind, long inputTokens, long outputTokens) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "kind",
                kind,
                "detail",
                "the agent burned tokens before it failed",
                "usage",
                Map.of("input_tokens", inputTokens, "output_tokens", outputTokens)));
    }

    /** A stubbed non-2xx {@code HttpResponse<String>} — {@code java.net.http.HttpResponse} is an
     *  interface, so Mockito can stand one in without a socket. */
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> respondWith(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    private static E2bTriageSandbox sandbox(LlmUsageAccountant usage, HttpResponse<String> canned) {
        return new E2bTriageSandbox(props(), noLaneSetting(), credentials(), usage, OpenTelemetry.noop(), MAPPER) {
            @Override
            HttpResponse<String> send(HttpRequest req) {
                return canned;
            }
        };
    }

    // ---- classifyFailure: the pure split -------------------------------------------------------

    /**
     * 401, 403, and 404 are always misconfiguration. Only a 502 carries the launcher's run-versus-launcher split, so a
     * 502 without a known run kind (a proxy's own page) and any other 5xx are the launcher's fault.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "401 | ''                         | MISCONFIGURED",
                "403 | ''                         | MISCONFIGURED",
                "404 | {\"kind\":\"orchestration\"}  | MISCONFIGURED",
                "502 | {\"kind\":\"timeout\"}        | RUN",
                "502 | {\"kind\":\"script_exit\"}    | RUN",
                "502 | {\"kind\":\"bad_output\"}     | RUN",
                "502 | {\"kind\":\"bad_request\"}    | RUN",
                "502 | {\"kind\":\"orchestration\"}  | LAUNCHER_UNAVAILABLE",
                "502 | ''                         | LAUNCHER_UNAVAILABLE",
                "502 | <html>Bad Gateway</html>   | LAUNCHER_UNAVAILABLE",
                "502 | {\"kind\":\"something_new\"}  | LAUNCHER_UNAVAILABLE",
                "500 | {\"kind\":\"script_exit\"}    | LAUNCHER_UNAVAILABLE",
                "503 | {\"kind\":\"timeout\"}        | LAUNCHER_UNAVAILABLE",
                "400 | ''                         | RUN",
                "422 | {}                         | RUN"
            })
    void classifyFailureSplitsTheRunFromTheLauncher(int status, String body, E2bTriageSandbox.FailureClass expected) {
        assertEquals(expected, E2bTriageSandbox.classifyFailure(status, body));
    }

    // ---- run(): the classification wired into an actual thrown ruling -------------------------

    @Test
    void aScriptExitBodyThrowsARunFailureAndStillBooksUsage() throws Exception {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        E2bTriageSandbox sandbox = sandbox(usage, respondWith(502, errorBodyWithUsage("script_exit", 120, 40)));

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));

        assertEquals(ClassifierError.TRIAGE_RUN_INCOMPLETE, ex.error());
        verify(usage)
                .recordSandboxRun(
                        eq("proj"), any(), any(), any(), eq(false), eq(120L), eq(40L), eq(0L), eq(0L), any(), any());
    }

    @Test
    void anOrchestrationBodyThrowsLauncherUnavailable() throws Exception {
        E2bTriageSandbox sandbox = sandbox(
                mock(LlmUsageAccountant.class), respondWith(502, errorBody("orchestration", "docker pull failed")));

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));

        assertEquals(ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE, ex.error());
    }

    @Test
    void a401ThrowsLauncherMisconfigured() throws Exception {
        E2bTriageSandbox sandbox = sandbox(mock(LlmUsageAccountant.class), respondWith(401, "unauthorized"));

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));

        assertEquals(ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED, ex.error());
    }

    // ---- run(): the launcher's answer read back into a ruling -----------------------------------

    /** An unset launcher is a misconfiguration somebody has to see, not a run that found nothing. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"  "})
    void anUnsetLauncherThrowsLauncherUnavailableBeforeAnyCall(String launcherUrl) {
        ObserverProperties bare = props();
        bare.getAgentic().setLauncherUrl(launcherUrl);
        E2bTriageSandbox sandbox = new E2bTriageSandbox(
                bare, noLaneSetting(), credentials(), mock(LlmUsageAccountant.class), OpenTelemetry.noop(), MAPPER);

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));

        assertEquals(ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE, ex.error());
    }

    /**
     * A completed run over a real loopback socket: the ruling text comes out of the {@code raw} envelope's
     * {@code result}, and the envelope's usage is booked against the finding. Missing either is a ruling
     * that never reaches the worker, or a triage agent whose spend the ledger never sees.
     */
    @Test
    void aCompletedRunReturnsTheAgentsResultAndBooksItsUsage() throws Exception {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        String raw = MAPPER.writeValueAsString(Map.of(
                "result",
                "{\"verdict\":\"positive\"}",
                "usage",
                Map.of("input_tokens", 100, "output_tokens", 20),
                "total_cost_usd",
                0.5));
        try (ServerSocket launcher =
                launcherAnswering(200, MAPPER.writeValueAsString(Map.of("raw", raw, "startMs", 0)))) {
            ObserverProperties live = props();
            live.getAgentic().setLauncherUrl("http://127.0.0.1:" + launcher.getLocalPort());
            E2bTriageSandbox sandbox =
                    new E2bTriageSandbox(live, noLaneSetting(), credentials(), usage, OpenTelemetry.noop(), MAPPER);

            assertEquals(
                    Optional.of(new TriageSandbox.SandboxRun("{\"verdict\":\"positive\"}")), sandbox.run(request()));
        }
        verify(usage)
                .recordSandboxRun(
                        eq("proj"),
                        any(),
                        any(),
                        any(),
                        eq(false),
                        eq(100L),
                        eq(20L),
                        eq(0L),
                        eq(0L),
                        eq(BigDecimal.valueOf(0.5)),
                        eq(new LlmUsageAccountant.Subject(E2bTriageSandbox.SUBJECT_KIND, "f1")));
    }

    /**
     * A 2xx that carries no ruling is an empty run, never a thrown one: no {@code raw} (a run-level failure
     * body), a {@code raw} with a blank {@code result}, or a body that is not JSON at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"kind\":\"timeout\"}", "{\"raw\":\"{\\\"result\\\":\\\"\\\"}\"}", "<html>proxy</html>"})
    void aSuccessfulAnswerWithNoRulingIsAnEmptyRun(String body) {
        E2bTriageSandbox sandbox = sandbox(mock(LlmUsageAccountant.class), respondWith(200, body));

        assertEquals(Optional.empty(), sandbox.run(request()));
    }

    /**
     * A read timeout is this run taking too long, so it is an empty run the finding's own attempts absorb.
     * A connect failure reached nothing, so it is the launcher's fault and feeds the breaker.
     */
    @Test
    void aTimeoutIsAnEmptyRunAndAConnectFailureIsTheLaunchers() {
        E2bTriageSandbox timesOut = throwing(new HttpTimeoutException("request timed out"));
        assertEquals(Optional.empty(), timesOut.run(request()));

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> throwing(new ConnectException("refused")).run(request()));
        assertEquals(ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE, ex.error());
        assertEquals(
                "The triage launcher is not answering: unreachable at http://launcher (ConnectException)",
                ex.getMessage());
    }

    /** Cancellation propagates as the thread's interrupt, never swallowed as "no ruling". */
    @Test
    void anInterruptedRunIsEmptyAndKeepsTheInterrupt() {
        try {
            assertEquals(Optional.empty(), throwing(new InterruptedException()).run(request()));
            assertTrue(Thread.interrupted(), "the interrupt is restored for the caller to see");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * A 404 is a launcher image older than the backend: the refusal says so, since no retry ships a new
     * image. The launcher's own diagnosis names the sandbox when it has one, alone or after kind and detail.
     */
    @Test
    void theRefusalsNameTheirRemedyAndTheSandbox() throws Exception {
        TessaryException notFound = assertThrows(
                TessaryException.class,
                () -> sandbox(mock(LlmUsageAccountant.class), respondWith(404, ""))
                        .run(request()));
        assertEquals(ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED, notFound.error());
        assertEquals(
                "The triage launcher refused the run: status 404 from http://launcher — no /triage route; the"
                        + " launcher image is older than this backend",
                notFound.getMessage());

        TessaryException scriptExit = assertThrows(
                TessaryException.class,
                () -> sandbox(
                                mock(LlmUsageAccountant.class),
                                respondWith(
                                        502,
                                        MAPPER.writeValueAsString(Map.of(
                                                "kind", "script_exit", "detail", "exit 1", "sandbox_id", "sb-1"))))
                        .run(request()));
        assertEquals(
                "Triage of finding 'f1' produced no ruling: kind=script_exit detail=exit 1 sandbox=sb-1",
                scriptExit.getMessage());

        TessaryException sandboxOnly = assertThrows(
                TessaryException.class,
                () -> sandbox(mock(LlmUsageAccountant.class), respondWith(400, "{\"sandbox_id\":\"sb-2\"}"))
                        .run(request()));
        assertEquals("Triage of finding 'f1' produced no ruling: sandbox=sb-2", sandboxOnly.getMessage());
    }

    private static E2bTriageSandbox throwing(Exception failure) {
        LlmUsageAccountant usage = mock(LlmUsageAccountant.class);
        return new E2bTriageSandbox(props(), noLaneSetting(), credentials(), usage, OpenTelemetry.noop(), MAPPER) {
            @Override
            HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
                if (failure instanceof IOException io) throw io;
                throw (InterruptedException) failure;
            }
        };
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
                "stub-triage-launcher");
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
}

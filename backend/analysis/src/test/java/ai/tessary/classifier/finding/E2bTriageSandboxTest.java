// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

    @Test
    void classifyFailure_401_403_404AreAlwaysMisconfigured() {
        assertEquals(E2bTriageSandbox.FailureClass.MISCONFIGURED, E2bTriageSandbox.classifyFailure(401, ""));
        assertEquals(E2bTriageSandbox.FailureClass.MISCONFIGURED, E2bTriageSandbox.classifyFailure(403, ""));
        assertEquals(
                E2bTriageSandbox.FailureClass.MISCONFIGURED,
                E2bTriageSandbox.classifyFailure(404, "{\"kind\":\"orchestration\"}"));
    }

    @Test
    void classifyFailure_502WithARunFailureKindIsARunFailure() {
        for (String kind : new String[] {"timeout", "script_exit", "bad_output", "bad_request"}) {
            assertEquals(
                    E2bTriageSandbox.FailureClass.RUN,
                    E2bTriageSandbox.classifyFailure(502, "{\"kind\":\"" + kind + "\"}"),
                    "kind=" + kind);
        }
    }

    @Test
    void classifyFailure_502WithOrchestrationIsLauncherUnavailable() {
        assertEquals(
                E2bTriageSandbox.FailureClass.LAUNCHER_UNAVAILABLE,
                E2bTriageSandbox.classifyFailure(502, "{\"kind\":\"orchestration\"}"));
    }

    @Test
    void classifyFailure_502WithNoOrUnknownKindIsLauncherUnavailable() {
        // A proxy's own 502 page, not the launcher's own classified body.
        assertEquals(E2bTriageSandbox.FailureClass.LAUNCHER_UNAVAILABLE, E2bTriageSandbox.classifyFailure(502, ""));
        assertEquals(
                E2bTriageSandbox.FailureClass.LAUNCHER_UNAVAILABLE,
                E2bTriageSandbox.classifyFailure(502, "<html>Bad Gateway</html>"));
        assertEquals(
                E2bTriageSandbox.FailureClass.LAUNCHER_UNAVAILABLE,
                E2bTriageSandbox.classifyFailure(502, "{\"kind\":\"something_new\"}"));
    }

    @Test
    void classifyFailure_anyOtherFiveXxIsLauncherUnavailableRegardlessOfKind() {
        // Only a 502 carries the launcher's own run-vs-launcher split; 500/503/etc. are its own fault.
        assertEquals(
                E2bTriageSandbox.FailureClass.LAUNCHER_UNAVAILABLE,
                E2bTriageSandbox.classifyFailure(500, "{\"kind\":\"script_exit\"}"));
        assertEquals(
                E2bTriageSandbox.FailureClass.LAUNCHER_UNAVAILABLE,
                E2bTriageSandbox.classifyFailure(503, "{\"kind\":\"timeout\"}"));
    }

    @Test
    void classifyFailure_otherFourXxIsARunFailure() {
        assertEquals(E2bTriageSandbox.FailureClass.RUN, E2bTriageSandbox.classifyFailure(400, ""));
        assertEquals(E2bTriageSandbox.FailureClass.RUN, E2bTriageSandbox.classifyFailure(422, "{}"));
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
}

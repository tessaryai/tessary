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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.ModelsDevProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.RcaProperties.Agentic;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.LoopbackHttpStub;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
                "2026-05-04T00:00:00Z",
                Map.of("movement.md", "the movement"),
                "investigate",
                "{}",
                "https://api.example/mcp",
                "tsy_a_secret",
                "report-1");
    }

    /**
     * A claude result envelope whose {@code result} is the agent's final JSON message, plus the schema-validated
     * object agent-stream.js emits when the reply satisfied the schema.
     */
    private static String envelope(String resultText, @Nullable String structuredJson) throws Exception {
        java.util.Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("result", resultText);
        if (structuredJson != null) inner.put("structured_output", MAPPER.readTree(structuredJson));
        inner.put("usage", Map.of("input_tokens", 10, "output_tokens", 5, "cost_usd", 0.42));
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
        E2bRcaSandbox sandbox = stubbed(bodyJson -> envelope(
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
            return envelope("{\"verdict\":\"behavior_change\"}", null);
        });

        RcaSandbox.SandboxRun run = sandbox.run(request());

        assertEquals("{\"verdict\":\"behavior_change\"}", run.resultText());
        var body = MAPPER.readTree(posted.toString());
        assertEquals("sha", body.path("head_sha").asText());
        assertEquals("2026-05-04T00:00:00Z", body.path("onset_at").asText());
        assertEquals("the movement", body.path("files").path("movement.md").asText());
        assertEquals("https://api.example/mcp", body.path("mcp").path("url").asText());
        assertEquals("tsy_a_secret", body.path("mcp").path("token").asText());
        assertTrue(body.path("clone_url").asText().contains("x-access-token"));
        // {@code provider} is always sent: no lane selection means BEDROCK, and its resolved credential rides along,
        // never logged.
        assertEquals("BEDROCK", body.path("provider").asText());
        assertFalse(body.path("credential").isMissingNode(), "credential must always be present");
    }

    /**
     * The lane wins over ObserverProperties' default, and names its provider: a non-Bedrock provider sends a bare
     * model id plus an explicit {@code provider}, since there is no Bedrock syntax to parse.
     */
    @ParameterizedTest
    @CsvSource({"BEDROCK, global.anthropic.claude-sonnet-4-6", "GEMINI, gemini-2.5-pro"})
    void projectRcaLaneOverridesTheObserverModel(ModelProvider provider, String model) throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(new ProjectModelSettings.ResolvedAgenticModel(
                        provider,
                        model,
                        ModelCatalog.modelsDevId(provider, model).orElse(null))));

        var body = postedBody(settings, request());

        assertEquals(model, body.path("model").asText());
        assertEquals(provider.name(), body.path("provider").asText());
    }

    /**
     * The run's models.dev rates ride on the request for the launcher to declare to OpenCode; without them a model
     * whose provider block is not a models.dev id runs at $0 and books unpriced.
     */
    @Test
    void theRequestCarriesTheRatesOfTheModelTheLaneRuns() throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(
                        new ProjectModelSettings.ResolvedAgenticModel(ModelProvider.GROK, "grok-4.6", "xai/grok-4.6")));

        var body = postedBody(settings, request());

        assertEquals(wire(RATES.cost("xai/grok-4.6").orElseThrow()), body.path("model_cost"));
    }

    /** The deployment default is a Bedrock inference-profile id, and is billed at that profile's rates. */
    @Test
    void anUnsetLaneCarriesTheRatesOfTheDeploymentDefaultOnBedrock() throws Exception {
        var body = postedBody(noLaneSetting(), request());

        String defaultModel = new ObserverProperties().getAgentic().getModel();
        assertEquals(wire(RATES.cost("amazon-bedrock/" + defaultModel).orElseThrow()), body.path("model_cost"));
    }

    /** A model models.dev cannot know sends no rates, and the run books unpriced rather than at some guess. */
    @Test
    void aCustomEndpointCarriesNoRates() throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(
                        new ProjectModelSettings.ResolvedAgenticModel(ModelProvider.CUSTOM, "my-model", null)));

        var body = postedBody(settings, request());

        assertTrue(body.path("model_cost").isMissingNode());
    }

    /** No git integration: the clone fields and the onset are omitted, not empty, because the script branches on
     *  their presence. */
    @Test
    void aRepolessRequestOmitsTheCloneFields() throws Exception {
        var body = postedBody(
                noLaneSetting(),
                new RcaSandbox.SandboxRequest(
                        "proj",
                        "g1",
                        null,
                        null,
                        null,
                        Map.of(),
                        "p",
                        "{}",
                        "https://api.example/mcp",
                        "tsy_a_secret",
                        "report-1"));

        assertTrue(body.path("clone_url").isMissingNode());
        assertTrue(body.path("head_sha").isMissingNode());
        assertTrue(body.path("onset_at").isMissingNode());
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
                stubbed(noLaneSetting(), usage, bodyJson -> envelope("{\"verdict\":\"behavior_change\"}", null));

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
        try (LoopbackHttpStub launcher = LoopbackHttpStub.answering(
                502,
                "{\"error\":\"sandbox orchestration failed\",\"kind\":\"agent_failed\","
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}")) {
            RcaProperties p = launcherAt(launcher.baseUrl());
            E2bRcaSandbox sandbox = sandbox(p, usage);

            TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
            assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        }

        verifyBooked(usage, 7, 3, null);
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
        RcaProperties p = launcherAt("http://127.0.0.1:" + port);

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
        try (LoopbackHttpStub launcher = LoopbackHttpStub.answering(502, body)) {
            RcaProperties p = launcherAt(launcher.baseUrl());

            TessaryException ex =
                    assertThrows(TessaryException.class, () -> sandbox(p).run(request()));

            assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
            assertTrue(ex.getMessage().endsWith(expected), ex.getMessage());
        }
    }

    /** Catches a 2xx body not being where the result is read from. */
    @Test
    void anAcceptedRunReadsItsResultFromTheLaunchersBody() throws Exception {
        try (LoopbackHttpStub launcher =
                LoopbackHttpStub.answering(200, envelope("{\"summary\":\"from the launcher\"}", null))) {
            RcaProperties p = launcherAt(launcher.baseUrl());

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
            RcaProperties p = launcherAt("http://127.0.0.1:" + silent.getLocalPort());
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

    private static final AgenticCredentialResolver.Credential LEASED = new AgenticCredentialResolver.Credential(
            ModelProvider.ANTHROPIC, null, null, null, null, null, null, true, "secret", "lease-1");

    /**
     * A resolver that reserves something per run frees it in {@code release}; a finished run and a
     * failed one must both hand it back, or the reservation leaks until it expires.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theRunsCredentialIsReleasedWhetherTheRunSucceedsOrFails(boolean launcherFails) throws Exception {
        String completed = envelope("{\"causes\":[]}", null);
        AgenticCredentialResolver resolver = mock(AgenticCredentialResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(LEASED);
        E2bRcaSandbox sandbox = stubbed(noLaneSetting(), resolver, mock(LlmUsageAccountant.class), bodyJson -> {
            if (launcherFails) throw new IOException("launcher went away");
            return completed;
        });

        try {
            sandbox.run(request());
        } catch (TessaryException expectedForTheFailingLauncher) {
            // The outcome is not this test's subject; the release is.
        }

        verify(resolver, times(1)).release(LEASED);
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
        return stubbed(settings, credentials(), usage, launcher);
    }

    private static E2bRcaSandbox stubbed(
            ProjectModelSettings settings,
            AgenticCredentialResolver resolver,
            LlmUsageAccountant usage,
            Launcher launcher) {
        return new E2bRcaSandbox(
                props(), new ObserverProperties(), settings, resolver, usage, RATES, OpenTelemetry.noop(), MAPPER) {
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

    private static JsonNode postedBody(ProjectModelSettings settings, RcaSandbox.SandboxRequest request)
            throws Exception {
        StringBuilder posted = new StringBuilder();
        stubbed(settings, mock(LlmUsageAccountant.class), bodyJson -> {
                    posted.append(bodyJson);
                    return envelope("{}", null);
                })
                .run(request);
        return MAPPER.readTree(posted.toString());
    }

    /** The real lookup over the bundled models.dev copy; a blank URL never fetches. */
    private static final ModelsDevRates RATES = bundledRates();

    /** The cost as the launcher reads it off the wire. */
    private static JsonNode wire(ModelsDevRates.ModelCost cost) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(cost.toOpencodeCost(MAPPER)));
    }

    private static ModelsDevRates bundledRates() {
        ModelsDevProperties p = new ModelsDevProperties();
        p.setUrl("");
        return new ModelsDevRates(new ObjectMapper(), p);
    }

    private static E2bRcaSandbox sandbox(RcaProperties p) {
        return sandbox(p, mock(LlmUsageAccountant.class));
    }

    private static E2bRcaSandbox sandbox(RcaProperties p, LlmUsageAccountant usage) {
        return new E2bRcaSandbox(
                p,
                new ObserverProperties(),
                noLaneSetting(),
                credentials(),
                usage,
                RATES,
                OpenTelemetry.noop(),
                MAPPER);
    }

    private static RcaProperties launcherAt(String url) {
        RcaProperties p = props();
        p.getAgentic().setLauncherUrl(url);
        return p;
    }

    private static void verifyBooked(LlmUsageAccountant usage, long in, long out, @Nullable BigDecimal cost) {
        verify(usage)
                .recordSandboxRun(
                        eq("proj"),
                        eq("rca"),
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

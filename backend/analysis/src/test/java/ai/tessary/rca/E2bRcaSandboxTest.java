// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.config.ObserverProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.RcaProperties.Agentic;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

    /** #939 D4: every run resolves + injects an org credential now — a lenient stub covering
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
        // #939 D4: `provider` is ALWAYS sent now (never omitted) — there is no deployment-wide
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
                        ai.tessary.llm.ModelProvider.BEDROCK, "global.anthropic.claude-sonnet-4-6")));

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
        // #939: an explicit lane selection also names its provider, so the launcher's providerConfig()
        // knows which block to build without having to parse the model id's shape.
        assertEquals("BEDROCK", body.path("provider").asText());
    }

    /**
     * #939: a project pointed at one of the four new non-Bedrock providers sends a bare model id
     * (not a Bedrock inference-profile string) plus an explicit {@code provider} field — the launcher
     * has no Bedrock-syntax hint to parse for these, so the field is how it knows what to build.
     */
    @Test
    void projectRcaLaneOnANonBedrockProvider_sendsTheBareModelIdAndProviderField() throws Exception {
        ProjectModelSettings settings = mock(ProjectModelSettings.class);
        when(settings.resolveAgenticModel("proj", ModelLane.RCA))
                .thenReturn(Optional.of(new ProjectModelSettings.ResolvedAgenticModel(
                        ai.tessary.llm.ModelProvider.GEMINI, "gemini-2.5-pro")));

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

    @Test
    void launcherFailurePropagatesAsUpstreamFailed() {
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
                        throw new TessaryException(RcaError.UPSTREAM_FAILED, "agentic RCA launcher HTTP 502");
                    }
                };

        TessaryException ex = assertThrows(TessaryException.class, () -> sandbox.run(request()));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
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

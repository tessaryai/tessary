// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.RcaProperties;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProvider;
import ai.tessary.git.GitProviderClient;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.git.GitTokenService;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AgenticRcaEngine#validateSandboxConfig()} — this class is the only coverage of it. Only the
 * pre-existing sandbox-key check is covered here: an equivalent unconditional blank-{@code mcp-base-url}
 * check was considered and rejected for this method — see its javadoc for why (it would fail context
 * refresh on every {@code @SpringBootTest} in {@code app} that does not fake the property, proven
 * concretely with {@code OpenApiSpecDriftTest}). The per-job throw in {@link AgenticRcaEngine#run}
 * remains the enforcement point for a blank URL.
 *
 * <p>{@link AgenticRcaEngine#run} is covered for the groundedness lane: it picks the prompt, the schema and
 * the parser by report kind, and nothing else checks that switch end to end.
 */
@ExtendWith(MockitoExtension.class)
class AgenticRcaEngineTest {

    @Mock
    ApiKeyService apiKeys;

    /** A minimal {@link RcaSandbox} test double keyed "e2b" — the default {@code RcaProperties} picks. */
    private static RcaSandbox fixedSandbox() {
        return new RcaSandbox() {
            @Override
            public String key() {
                return "e2b";
            }

            @Override
            public RcaSandbox.SandboxRun run(RcaSandbox.SandboxRequest req) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    private AgenticRcaEngine engine(RcaProperties props) {
        return new AgenticRcaEngine(
                props,
                List.of(fixedSandbox()),
                mock(GitIntegrationRepository.class),
                mock(GitProviderFactory.class),
                mock(ApiKeyService.class),
                new ObjectMapper());
    }

    @Test
    void validateSandboxConfigRejectsAnUnregisteredSandboxKey() {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setSandbox("no-such-driver");

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> engine(props).validateSandboxConfig());
        assertNotNull(e.getMessage());
        assertEquals(true, e.getMessage().contains("no-such-driver"));
    }

    /** A sandbox that records what it was handed and replies with a canned final message. */
    private static final class RecordingSandbox implements RcaSandbox {
        final List<RcaSandbox.SandboxRequest> requests = new ArrayList<>();
        private final String reply;

        RecordingSandbox(String reply) {
            this.reply = reply;
        }

        @Override
        public String key() {
            return "e2b";
        }

        @Override
        public RcaSandbox.SandboxRun run(RcaSandbox.SandboxRequest req) {
            requests.add(req);
            return new RcaSandbox.SandboxRun(reply);
        }
    }

    /** No git integration: the run is evidence-only. */
    private static GitIntegrationRepository noRepo() {
        return new GitIntegrationRepository(mock(JdbcClient.class)) {
            @Override
            public Optional<GitIntegrationRow> findByProject(String projectId) {
                return Optional.empty();
            }
        };
    }

    @Test
    void aGroundednessRunUsesTheGroundednessPromptSchemaAndParser() {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setMcpBaseUrl("https://tessary.test/");
        RcaJobRow job = new RcaJobRow("job-1", "proj-1", "fnd-1", "finding", "fnd-1", "groundedness_rate", "user-1");
        RcaReportRow report = groundednessReport();
        when(apiKeys.issue("proj-1", "user-1", "rca-job-1", KeyScope.ADMIN))
                .thenReturn(new ApiKeyService.Issued(
                        new ApiKey(
                                "key-1",
                                "proj-1",
                                "user-1",
                                "rca-job-1",
                                "tsk_",
                                "hash",
                                "2026-05-08T01:00:00Z",
                                null,
                                null,
                                "admin",
                                null,
                                null,
                                null),
                        "tsk_plain"));
        // tr-1 is a flagged trace of this finding; a groundedness cause cites traces and no sessions, so the
        // frustration parser would drop it and downgrade the verdict.
        RecordingSandbox sandbox = new RecordingSandbox("{\"summary\":\"One document per answer\","
                + "\"verdict\":\"causes_identified\",\"detailed_report\":\"## r\",\"checklist\":[],"
                + "\"causes\":[{\"title\":\"Retrieves one document\",\"what_the_agent_did\":\"w\","
                + "\"traces_affected\":2,\"evidence_trace_ids\":[\"tr-1\"],"
                + "\"attribution\":{\"kind\":\"code\",\"path\":\"rag/retrieve.py\",\"commit\":\"abc123\","
                + "\"excerpt\":\"top_k=1\"},\"fix_suggestion\":\"f\",\"confidence\":\"medium\"}]}");
        AgenticRcaEngine engine = new AgenticRcaEngine(
                props, List.of(sandbox), noRepo(), mock(GitProviderFactory.class), apiKeys, new ObjectMapper());
        Map<String, String> dossier = Map.of("finding.md", "# f");

        AgenticRcaEngine.Result result =
                engine.run(job, report, "fnd-1", dossier, Set.of(), Set.of("tr-1", "tr-2"), Set.of(), Set.of());

        assertEquals(
                List.of(new RcaSandbox.SandboxRequest(
                        "proj-1",
                        "fnd-1",
                        null,
                        null,
                        dossier,
                        AgenticRcaEngine.buildGroundednessPrompt(report, "fnd-1", false, 2),
                        AgenticRcaEngine.GROUNDEDNESS_JSON_SCHEMA,
                        "https://tessary.test/mcp",
                        "tsk_plain",
                        "rpt-1")),
                sandbox.requests);
        assertEquals(
                new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.CAUSES_IDENTIFIED,
                        "One document per answer",
                        List.of(),
                        List.of(new RcaDtos.Cause(
                                "Retrieves one document",
                                "w",
                                0,
                                2,
                                List.of(),
                                List.of("tr-1"),
                                new RcaDtos.Attribution("code", "rag/retrieve.py", "abc123", "top_k=1"),
                                "f",
                                "medium")),
                        List.of(),
                        "## r",
                        false),
                result);
    }

    /**
     * Catches the default (metric-movement) arm handing the agent another lane's prompt or schema, and a
     * downgraded verdict that is only logged: the note must lead the report an engineer reads, and a reply
     * with no {@code detailed_report} must fall back to its summary rather than render an empty page.
     */
    @Test
    void aMetricMovementRunUsesTheDefaultPromptAndLeadsItsReportWithTheDowngrade() {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setMcpBaseUrl("https://tessary.test");
        when(apiKeys.issue("proj-1", "user-1", "rca-job-1", KeyScope.ADMIN)).thenReturn(issuedKey());
        // behavior_change is comparative, the finding has a baseline side, and no hypothesis cites it.
        RecordingSandbox sandbox = new RecordingSandbox(
                "{\"summary\":\"The flagged side changed\",\"verdict\":\"behavior_change\",\"checklist\":[]}");
        AgenticRcaEngine engine = new AgenticRcaEngine(
                props, List.of(sandbox), noRepo(), mock(GitProviderFactory.class), apiKeys, new ObjectMapper());
        RcaReportRow report = report(RcaReportRow.ReportKind.METRIC_MOVEMENT);

        AgenticRcaEngine.Result result = engine.run(
                job(), report, "fnd-1", Map.of(), Set.of("tb-1"), Set.of("tf-1", "tf-2"), Set.of(), Set.of());

        RcaSandbox.SandboxRequest sent = sandbox.requests.get(0);
        assertEquals(AgenticRcaEngine.buildPrompt(report, "fnd-1", false, 1, 2), sent.prompt());
        assertEquals(AgenticRcaEngine.JSON_SCHEMA, sent.jsonSchema());
        assertEquals(RcaReportRow.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(List.of(), result.hypotheses());
        assertTrue(result.detailedReport().startsWith("> **Verdict downgraded by the platform.**"));
        assertTrue(result.detailedReport().endsWith("\n\nThe flagged side changed"));
    }

    /** Catches a frustration run handed the metric-movement prompt, schema or parser, which cites no sessions. */
    @Test
    void aFrustrationRunUsesTheFrustrationPromptSchemaAndParser() {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setMcpBaseUrl("https://tessary.test");
        when(apiKeys.issue("proj-1", "user-1", "rca-job-1", KeyScope.ADMIN)).thenReturn(issuedKey());
        RecordingSandbox sandbox = new RecordingSandbox("{\"summary\":\"s\",\"verdict\":\"no_cause_found\","
                + "\"detailed_report\":\"## d\",\"checklist\":[],\"causes\":[]}");
        AgenticRcaEngine engine = new AgenticRcaEngine(
                props, List.of(sandbox), noRepo(), mock(GitProviderFactory.class), apiKeys, new ObjectMapper());
        RcaReportRow report = report(RcaReportRow.ReportKind.FRUSTRATION_CAUSES);

        AgenticRcaEngine.Result result =
                engine.run(job(), report, "fnd-1", Map.of(), Set.of(), Set.of("tr-1"), Set.of("s-1", "s-2"), Set.of());

        RcaSandbox.SandboxRequest sent = sandbox.requests.get(0);
        assertEquals(AgenticRcaEngine.buildFrustrationPrompt(report, "fnd-1", false, 2, 1), sent.prompt());
        assertEquals(AgenticRcaEngine.FRUSTRATION_JSON_SCHEMA, sent.jsonSchema());
        assertEquals(
                new AgenticRcaEngine.Result(
                        RcaReportRow.Verdict.NO_CAUSE_FOUND, "s", List.of(), List.of(), List.of(), "## d", false),
                result);
    }

    /**
     * Catches a connected repo not reaching the sandbox (no clone URL, or not at its head), a repo whose token
     * cannot be minted failing the run instead of running evidence-only, and a key revocation that fails
     * throwing away a finished analysis.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"'Bearer ghs_1', https://x-access-token:ghs_1@github.com/acme/web.git, sha-1", "NULL, NULL, NULL"})
    void aConnectedRepoIsHandedOverAtItsHeadAndAFailedRevocationDoesNotSinkTheRun(
            String authHeader, String expectedCloneUrl, String expectedSha) {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setMcpBaseUrl("https://tessary.test");
        when(apiKeys.issue("proj-1", "user-1", "rca-job-1", KeyScope.ADMIN)).thenReturn(issuedKey());
        when(apiKeys.revoke("key-1", "user-1")).thenThrow(new IllegalStateException("audit write failed"));
        RecordingSandbox sandbox = new RecordingSandbox(
                "{\"summary\":\"s\",\"verdict\":\"inconclusive\",\"detailed_report\":\"## r\",\"checklist\":[]}");
        GitIntegrationRepository repo = new GitIntegrationRepository(mock(JdbcClient.class)) {
            @Override
            public Optional<GitIntegrationRow> findByProject(String projectId) {
                return Optional.of(
                        new GitIntegrationRow("i1", projectId, "github", null, "acme", "web", "main", "enc", "t", "t"));
            }
        };
        GitProviderFactory providers =
                new GitProviderFactory(List.of(new HeadAt("sha-1")), List.of(new Header(authHeader)));
        AgenticRcaEngine engine =
                new AgenticRcaEngine(props, List.of(sandbox), repo, providers, apiKeys, new ObjectMapper());
        RcaReportRow report = report(RcaReportRow.ReportKind.METRIC_MOVEMENT);

        AgenticRcaEngine.Result result =
                engine.run(job(), report, "fnd-1", Map.of(), Set.of(), Set.of("tf-1"), Set.of(), Set.of());

        RcaSandbox.SandboxRequest sent = sandbox.requests.get(0);
        assertEquals(expectedCloneUrl, sent.cloneUrl());
        assertEquals(expectedSha, sent.headSha());
        assertEquals(AgenticRcaEngine.buildPrompt(report, "fnd-1", expectedCloneUrl != null, 0, 1), sent.prompt());
        assertEquals(expectedCloneUrl != null, result.repoAvailable());
        assertEquals("## r", result.detailedReport());
    }

    /**
     * Catches a run started with no way to read the evidence: without the MCP door the agent can only
     * paraphrase the detector, so it is refused before a key is minted for it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void withoutAnEvidenceDoorTheRunIsRefusedBeforeAnyKeyIsIssued(String mcpBaseUrl) {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setMcpBaseUrl(mcpBaseUrl);
        RecordingSandbox sandbox = new RecordingSandbox("{}");
        AgenticRcaEngine engine = new AgenticRcaEngine(
                props, List.of(sandbox), noRepo(), mock(GitProviderFactory.class), apiKeys, new ObjectMapper());
        RcaReportRow report = report(RcaReportRow.ReportKind.METRIC_MOVEMENT);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> engine.run(job(), report, "fnd-1", Map.of(), Set.of(), Set.of("tf-1"), Set.of(), Set.of()));

        assertEquals(RcaError.NO_EVIDENCE_DOOR, e.error());
        assertEquals(List.of(), sandbox.requests);
        verifyNoInteractions(apiKeys);
    }

    /** A token service that answers with a fixed header, or refuses when there is none. */
    private record Header(@Nullable String value) implements GitTokenService {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public String authHeader(GitIntegrationRow integ) {
            if (value == null) throw new IllegalStateException("installation not authorized");
            return value;
        }
    }

    /** A provider client whose default branch is at a fixed commit. */
    private record HeadAt(String sha) implements GitProviderClient {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public RepoAccess verifyAccess(GitIntegrationRow integ) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public String resolveHeadSha(GitIntegrationRow integ, String branch) {
            return sha;
        }
    }

    private static RcaJobRow job() {
        return new RcaJobRow("job-1", "proj-1", "fnd-1", "finding", "fnd-1", "behavior_drift", "user-1");
    }

    private static ApiKeyService.Issued issuedKey() {
        return new ApiKeyService.Issued(
                new ApiKey(
                        "key-1",
                        "proj-1",
                        "user-1",
                        "rca-job-1",
                        "tsk_",
                        "hash",
                        "2026-05-08T01:00:00Z",
                        null,
                        null,
                        "admin",
                        null,
                        null,
                        null),
                "tsk_plain");
    }

    /** {@link #groundednessReport()} under another report kind. */
    private static RcaReportRow report(String kind) {
        RcaReportRow g = groundednessReport();
        return new RcaReportRow(
                g.id(),
                g.jobId(),
                g.subjectKind(),
                g.subjectId(),
                g.subjectLabel(),
                g.callSiteId(),
                g.metric(),
                kind,
                g.windowFrom(),
                g.windowSplit(),
                g.windowTo(),
                g.currentValue(),
                g.priorValue(),
                g.delta(),
                g.status(),
                g.verdict(),
                g.summary(),
                g.ruledOut(),
                g.hypotheses(),
                g.causes(),
                g.detailedReport(),
                g.engine(),
                g.repoAvailable(),
                g.createdAt(),
                g.completedAt());
    }

    private static RcaReportRow groundednessReport() {
        return new RcaReportRow(
                "rpt-1",
                "job-1",
                "finding",
                "fnd-1",
                "groundedness on answer",
                "cs-answer",
                "groundedness_rate",
                RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES,
                "2026-05-01T00:00:00Z",
                "2026-05-04T00:00:00Z",
                "2026-05-08T00:00:00Z",
                0.3,
                0.02,
                0.28,
                "running",
                null,
                null,
                null,
                null,
                null,
                null,
                RcaReportRow.Engine.AGENTIC,
                null,
                "2026-05-08T01:00:00Z",
                null);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.config.RcaProperties;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
                mock(ProjectRepository.class),
                mock(OrgMembershipRepository.class),
                new ObjectMapper());
    }

    @Test
    void validateSandboxConfigPassesWithARegisteredSandbox() {
        RcaProperties props = new RcaProperties();
        // Default is already "e2b" (RcaProperties.Agentic#sandbox), asserted explicitly so this test
        // fails loudly if that default ever drifts.
        assertEquals("e2b", props.getAgentic().getSandbox());

        assertDoesNotThrow(() -> engine(props).validateSandboxConfig());
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
        RcaJobRow job = new RcaJobRow(
                "job-1",
                "proj-1",
                "fnd-1",
                "finding",
                "fnd-1",
                "groundedness_rate",
                "2026-05-01T00:00:00Z",
                "2026-05-04T00:00:00Z",
                "2026-05-08T00:00:00Z",
                "user-1",
                "claimed",
                "worker-1",
                null,
                1,
                null,
                "2026-05-08T01:00:00Z",
                "2026-05-08T01:00:00Z");
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
                props,
                List.of(sandbox),
                noRepo(),
                mock(GitProviderFactory.class),
                apiKeys,
                mock(ProjectRepository.class),
                mock(OrgMembershipRepository.class),
                new ObjectMapper());
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

    private static RcaReportRow groundednessReport() {
        return new RcaReportRow(
                "rpt-1",
                "proj-1",
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

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.config.ClassifierProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link BehaviorTriageEngine} against a {@link TriageSandbox} test double — no launcher, no E2B, no
 * Spring context. Exercises the two facts that would otherwise silently rot if a future edit collapsed
 * the sandbox seam: a normal ruling round-trips through {@code TriageSandbox.SandboxRequest}/
 * {@code SandboxRun}, and an empty run (the sandbox's documented "ran, produced nothing" contract)
 * surfaces as {@code TRIAGE_RUN_INCOMPLETE} rather than a fabricated ruling.
 */
class BehaviorTriageEngineTest {

    private static final String PROJECT_ID = "proj1";
    private static final String ORG_ID = "org1";
    private static final String FINDING_ID = "finding1";

    private final ObjectMapper mapper = new ObjectMapper();

    private static ClassifierProperties props() {
        ClassifierProperties p = new ClassifierProperties();
        p.setTriageMcpBaseUrl("https://app.tessary.ai");
        // Default is already "e2b" (ClassifierProperties#triageSandbox), asserted explicitly so this
        // test fails loudly if that default ever drifts.
        assertEquals("e2b", p.getTriageSandbox());
        return p;
    }

    private ApiKeyService apiKeys() {
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        ApiKey key = new ApiKey(
                "key1",
                PROJECT_ID,
                "user1",
                "triage",
                "tsy_a_prefix",
                "hash",
                "2026-01-01T00:00:00Z",
                null,
                null,
                "admin",
                null,
                null,
                null);
        when(apiKeys.issue(any(), any(), any(), any())).thenReturn(new ApiKeyService.Issued(key, "tsy_a_secret"));
        return apiKeys;
    }

    private ProjectRepository projects() {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, ORG_ID, "slug", "name", null, "2026-01-01T00:00:00Z", null, null, false, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        return projects;
    }

    private OrgMembershipRepository memberships() {
        OrgMembershipRepository memberships = mock(OrgMembershipRepository.class);
        when(memberships.findByOrg(ORG_ID))
                .thenReturn(List.of(OrgMembership.of(ORG_ID, "user1", OrgMembership.OWNER, "2026-01-01T00:00:00Z")));
        return memberships;
    }

    /** A {@link TriageSandbox} test double keyed "e2b" — the default {@code ClassifierProperties} picks. */
    private static TriageSandbox fixedSandbox(Optional<TriageSandbox.SandboxRun> result) {
        return new TriageSandbox() {
            @Override
            public String key() {
                return "e2b";
            }

            @Override
            public Optional<TriageSandbox.SandboxRun> run(TriageSandbox.SandboxRequest req) {
                assertEquals(PROJECT_ID, req.projectId());
                assertEquals(FINDING_ID, req.findingId());
                return result;
            }
        };
    }

    @Test
    void normalRulingRoundTripsThroughTheSandboxAndProducesAVerdict() {
        String resultJson = """
                {"verdict": "positive", "summary": "both windows measure the same population",
                 "citations": [{"path": "window.n_cur", "reason": "1,204 turns, not a thin window"}]}
                """;
        BehaviorTriageEngine engine = new BehaviorTriageEngine(
                List.of(fixedSandbox(Optional.of(new TriageSandbox.SandboxRun(resultJson)))),
                props(),
                apiKeys(),
                projects(),
                memberships(),
                mapper,
                mock(FindingEvidenceRepository.class));

        BehaviorTriageVerdict verdict =
                engine.rule(PROJECT_ID, FINDING_ID, Map.of("finding.md", "the claim"), "rule on this", null);

        assertNotNull(verdict);
        assertEquals(FindingRow.TriageVerdict.POSITIVE, verdict.verdict());
        assertEquals(1, verdict.citations().size());
    }

    @Test
    void emptyRunSurfacesAsTriageRunIncomplete() {
        // The sandbox's own documented contract: an empty Optional means "ran, produced nothing" — the
        // engine must throw so the job retries, never fabricate an `unclear` ruling for a run that
        // simply did not happen.
        BehaviorTriageEngine engine = new BehaviorTriageEngine(
                List.of(fixedSandbox(Optional.empty())),
                props(),
                apiKeys(),
                projects(),
                memberships(),
                mapper,
                mock(FindingEvidenceRepository.class));

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> engine.rule(PROJECT_ID, FINDING_ID, Map.of("finding.md", "the claim"), "rule on this", null));

        assertEquals(ClassifierError.TRIAGE_RUN_INCOMPLETE, e.error());
    }
}

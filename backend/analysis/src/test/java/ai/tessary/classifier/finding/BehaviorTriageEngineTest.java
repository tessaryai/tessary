// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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
                assertEquals(
                        BehaviorTriageEngine.SYSTEM_PROMPT,
                        req.systemPrompt(),
                        "the shared system prompt rides every run");
                return result;
            }
        };
    }

    @Test
    void emptyRunSurfacesAsTriageRunIncomplete() {
        // The sandbox's own documented contract: an empty Optional means "ran, produced nothing" — the
        // engine must throw so the job retries, never fabricate a ruling for a run that simply did not
        // happen.
        BehaviorTriageEngine engine = new BehaviorTriageEngine(
                List.of(fixedSandbox(Optional.empty())),
                props(),
                new ObserverProperties(),
                apiKeys(),
                projects(),
                memberships(),
                mapper,
                mock(ClassifierDetectionWriteRepository.class));

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> engine.rule(PROJECT_ID, FINDING_ID, Map.of("finding.md", "the claim"), "rule on this"));

        assertEquals(ClassifierError.TRIAGE_RUN_INCOMPLETE, e.error());
    }

    /** A sandbox key nothing registered refuses to boot, rather than failing every finding's run later. */
    @Test
    void anUnregisteredSandboxKeyRefusesToBoot() {
        ClassifierProperties props = props();
        props.setTriageSandbox("firecracker");

        assertThrows(
                IllegalStateException.class,
                () -> engine(props, fixedSandbox(Optional.empty()), memberships())
                        .validateSandboxConfig());
    }

    /**
     * Without the MCP door the agent cannot read a single row it is auditing, so the run is refused as
     * incomplete before any key is minted or sandbox booted.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" "})
    void anUnsetMcpDoorIsAnIncompleteRun(@Nullable String mcpBase) {
        ClassifierProperties props = props();
        props.setTriageMcpBaseUrl(mcpBase);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> engine(props, fixedSandbox(Optional.empty()), memberships())
                        .rule(PROJECT_ID, FINDING_ID, Map.of(), "rule on this"));

        assertEquals(
                "Triage of finding 'finding1' produced no ruling: tessary.classifier.triage-mcp-base-url is unset, so"
                        + " the agent has no way to read the evidence",
                e.getMessage());
    }

    /** A key is issued to the org's owner; an org with none gets no key and no run. */
    @Test
    void anOrgWithNoOwnerGetsNoRun() {
        OrgMembershipRepository memberships = mock(OrgMembershipRepository.class);
        when(memberships.findByOrg(ORG_ID))
                .thenReturn(List.of(OrgMembership.of(ORG_ID, "user1", OrgMembership.MEMBER, "2026-01-01T00:00:00Z")));

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> engine(props(), fixedSandbox(Optional.empty()), memberships)
                        .rule(PROJECT_ID, FINDING_ID, Map.of(), "rule on this"));

        assertEquals(
                "Triage of finding 'finding1' produced no ruling: the project's org has no owner to issue a key to",
                e.getMessage());
    }

    /**
     * An answer that carries no ruling is an incomplete run that names why: the agent's own blocked reason
     * when it reported one, and a plain "no parseable ruling" otherwise. Recording either as a verdict would
     * be a ruling nothing established.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"verdict\":\"blocked\",\"summary\":\"the MCP door refused the key\"}|the MCP door refused the key",
                "I could not decide.|the agent returned no parseable ruling",
            })
    void anAnswerWithNoRulingIsAnIncompleteRunThatSaysWhy(String answer, String why) {
        BehaviorTriageEngine engine =
                engine(props(), fixedSandbox(Optional.of(new TriageSandbox.SandboxRun(answer))), memberships());

        TessaryException e = assertThrows(
                TessaryException.class, () -> engine.rule(PROJECT_ID, FINDING_ID, Map.of(), "rule on this"));

        assertEquals("Triage of finding 'finding1' produced no ruling: " + why, e.getMessage());
    }

    /** Revoking the run's key failing must not throw away the ruling the run produced. */
    @Test
    void aFailedKeyRevokeKeepsTheRuling() {
        ApiKeyService apiKeys = apiKeys();
        doThrow(new IllegalStateException("db blip")).when(apiKeys).revoke("key1");
        String answer =
                "{\"verdict\":\"negative\",\"summary\":\"s\",\"citations\":[{\"path\":\"a\",\"reason\":\"b\"}]}";
        BehaviorTriageEngine engine = new BehaviorTriageEngine(
                List.of(fixedSandbox(Optional.of(new TriageSandbox.SandboxRun(answer)))),
                props(),
                new ObserverProperties(),
                apiKeys,
                projects(),
                memberships(),
                mapper,
                mock(ClassifierDetectionWriteRepository.class));

        BehaviorTriageVerdict verdict = engine.rule(PROJECT_ID, FINDING_ID, Map.of(), "rule on this");

        assertEquals(
                new BehaviorTriageVerdict(
                        FindingRow.TriageVerdict.NEGATIVE,
                        "s",
                        List.of(new BehaviorTriageVerdict.Citation("a", "b", null))),
                verdict);
    }

    /**
     * The citations blob the case page reads back: every citation, a script's stdout kept as its receipt and
     * a pointer's written as null.
     */
    @Test
    void citationsSerializeWithAScriptsStdout() {
        BehaviorTriageVerdict verdict = new BehaviorTriageVerdict(
                FindingRow.TriageVerdict.POSITIVE,
                "s",
                List.of(
                        new BehaviorTriageVerdict.Citation("window.n_cur", "the count", null),
                        new BehaviorTriageVerdict.Citation("checks/by_model.py", "split by model", "haiku 41%")));

        assertEquals(
                "[{\"path\":\"window.n_cur\",\"reason\":\"the count\",\"stdout\":null},"
                        + "{\"path\":\"checks/by_model.py\",\"reason\":\"split by model\",\"stdout\":\"haiku 41%\"}]",
                engine(props(), fixedSandbox(Optional.empty()), memberships()).citationsJson(verdict));
    }

    /**
     * The bug: a ruling that cites nothing writes {@code []} to {@code finding.triage_citations}, where every
     * other uncited ruling carries NULL, so a query for uncited rulings ({@code triage_citations IS NULL})
     * misses it.
     */
    @Test
    void aRulingWithNoCitationsStoresNone() {
        BehaviorTriageVerdict verdict = new BehaviorTriageVerdict(FindingRow.TriageVerdict.POSITIVE, "s", List.of());

        assertNull(
                engine(props(), fixedSandbox(Optional.empty()), memberships()).citationsJson(verdict));
    }

    private BehaviorTriageEngine engine(
            ClassifierProperties props, TriageSandbox sandbox, OrgMembershipRepository memberships) {
        return new BehaviorTriageEngine(
                List.of(sandbox),
                props,
                new ObserverProperties(),
                apiKeys(),
                projects(),
                memberships,
                mapper,
                mock(ClassifierDetectionWriteRepository.class));
    }
}

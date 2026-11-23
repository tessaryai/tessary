// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.SessionEvent.PatchFile;
import ai.tessary.fix.FixDestination.Change;
import ai.tessary.fix.FixDestination.Delivery;
import ai.tessary.fix.FixDestination.Start;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.GitProviderClient.WriteAccess;
import ai.tessary.git.GitProviderClient.WriteBlock;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.git.GitTokenService;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The pull request destination against a repository in memory. The bugs it catches are the ones a reviewer pays for:
 * the agent handed the write token, a rerun starting from the default branch and dropping a person's commits, a rerun
 * that re-fixes causes the pull request already fixes, a closed pull request whose causes come back in the next one, a
 * force-push over a person's commit, and a change rebuilt on top of a commit that touched the same file.
 */
@ExtendWith(MockitoExtension.class)
class GithubPrDestinationTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private static final GitIntegrationRow INTEG = new GitIntegrationRow(
            "gi-1", "proj-1", "github", "api.github.com", "acme", "agent", "main", "sealed", "t0", "t0");

    private static final FixableCause RETRY = cause("code", "src/agent.py", "The search tool is never retried");
    private static final FixableCause PROMPT = cause("prompt", "prompts/system.md", "The prompt asks for prose");

    @Mock
    private GitIntegrationRepository integrations;

    @Mock
    private GitIntegrationService integrationService;

    @Mock
    private FixPrRepository prs;

    private final FakeGitProvider git = new FakeGitProvider();

    private GithubPrDestination destination() {
        return destination(git);
    }

    private GithubPrDestination destination(GitTokenService tokens) {
        return new GithubPrDestination(
                integrations,
                integrationService,
                new GitProviderFactory(List.of(git), List.of(tokens)),
                prs,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FixableCause cause(String type, String path, String title) {
        Cause c = new Cause(
                title,
                Cause.HIGH,
                "standing",
                type,
                "It happens.",
                "It caused the failures.",
                "Fix it.",
                new Attribution(null, path, null, null),
                List.of("tr-1"),
                List.of(),
                1,
                1);
        return FixableCause.of(List.of(c)).get(0);
    }

    private static FixPrRow pr(String id, String state, String branch, int number, List<String> causes) {
        return new FixPrRow(
                id,
                "proj-1",
                "case-1",
                branch,
                number,
                "https://github.com/acme/agent/pull/" + number,
                state,
                "commit-pr",
                causes,
                "t1",
                "t1");
    }

    private static PatchFile file(String path, String content) {
        return new PatchFile(
                path, "100644", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Change change(String baseSha, List<String> causes, PatchFile... files) {
        return new Change(List.of(files), baseSha, causes, "Retry the search tool", "the body", "Retry it\n");
    }

    @Test
    void aProjectWithNoRepositoryCannotTakeAFix() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.empty());

        assertEquals(FixRunRow.Reason.NO_REPO, destination().blocked("proj-1"));
    }

    @Test
    void aRepositoryTessaryCannotWriteToCannotTakeAFix() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(integrationService.writeAccess(INTEG)).thenReturn(WriteAccess.blocked(WriteBlock.TOKEN_CANNOT_PUSH));

        assertEquals(FixRunRow.Reason.READ_ONLY, destination().blocked("proj-1"));
    }

    @Test
    void aRepositoryTessaryCanWriteToTakesAFix() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(integrationService.writeAccess(INTEG)).thenReturn(WriteAccess.granted());

        assertNull(destination().blocked("proj-1"));
    }

    @Test
    void aRepositoryRemovedBeforeTheRunStopsItAsSkipped() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.empty());

        assertEquals(
                new Start.Stop(FixRunRow.Outcome.SKIPPED, FixRunRow.Reason.NO_REPO, null),
                destination().start("proj-1", "case-1", List.of(RETRY)));
    }

    @Test
    void aFirstRunClonesTheDefaultBranchHeadWithTheReadOnlyCloneToken() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of());

        Start start = destination().start("proj-1", "case-1", List.of(RETRY, PROMPT));

        assertEquals(
                new Start.Go(
                        "proj-1",
                        "case-1",
                        new AgentSessionClient.Clone(
                                "https://x-access-token:" + FakeGitProvider.CLONE_TOKEN + "@github.com/acme/agent.git",
                                "base",
                                null),
                        "main",
                        List.of(RETRY, PROMPT),
                        null),
                start);
    }

    @Test
    void aCloneTokenThatCannotBeMintedFailsTheRunRatherThanCloningWithTheWriteToken() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of());
        GitTokenService broken = new GitTokenService() {
            @Override
            public ai.tessary.git.GitProvider provider() {
                return ai.tessary.git.GitProvider.GITHUB;
            }

            @Override
            public String authHeader(GitIntegrationRow integ) {
                throw new IllegalStateException("the App's key is gone");
            }
        };

        TessaryException e = assertThrows(
                TessaryException.class, () -> destination(broken).start("proj-1", "case-1", List.of(RETRY)));

        assertEquals(GitError.TOKEN_MINT_FAILED, e.error());
    }

    @Test
    void aRerunOnAnOpenPullRequestStartsFromItsBranchWithOnlyTheNewCauses() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of(open));
        git.branches.put("tessary/fix-case-1", "base");
        git.createPullRequest(INTEG, "tessary/fix-case-1", "main", "t", "b");

        Start start = destination().start("proj-1", "case-1", List.of(RETRY, PROMPT));

        assertEquals(
                new Start.Go(
                        "proj-1",
                        "case-1",
                        AgentSessionClient.Clone.atBranch(
                                "https://x-access-token:" + FakeGitProvider.CLONE_TOKEN + "@github.com/acme/agent.git",
                                "tessary/fix-case-1"),
                        "tessary/fix-case-1",
                        List.of(PROMPT),
                        open),
                start);
    }

    @Test
    void aRerunWithNoNewCauseOnAnOpenPullRequestChangesNothing() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of(open));
        git.createPullRequest(INTEG, "tessary/fix-case-1", "main", "t", "b");

        assertEquals(
                new Start.Stop(FixRunRow.Outcome.NO_CHANGE, null, "pr-1"),
                destination().start("proj-1", "case-1", List.of(RETRY)));
    }

    @Test
    void aMergedPullRequestLetsALaterFailureOpenANewOneFromTheDefaultBranch() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of(open));
        git.createPullRequest(INTEG, "tessary/fix-case-1", "main", "t", "b");
        git.close(41, true);

        Start start = destination().start("proj-1", "case-1", List.of(RETRY));

        verify(prs).markState("pr-1", FixPrRow.State.MERGED, NOW);
        Start.Go go = assertInstanceOf(Start.Go.class, start);
        assertEquals("main", go.branch());
        assertEquals(List.of(RETRY), go.causes());
        assertNull(go.openPr());
    }

    @Test
    void aPullRequestClosedWithoutAMergeKeepsItsCausesOutOfTheNextOne() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of(open));
        git.createPullRequest(INTEG, "tessary/fix-case-1", "main", "t", "b");
        git.close(41, false);

        Start start = destination().start("proj-1", "case-1", List.of(RETRY, PROMPT));

        verify(prs).markState("pr-1", FixPrRow.State.CLOSED, NOW);
        assertEquals(List.of(PROMPT), assertInstanceOf(Start.Go.class, start).causes());
    }

    @Test
    void everyCauseOfEveryClosedPullRequestIsDeclinedSoNothingIsLeftToFix() {
        FixPrRow older = pr("pr-0", FixPrRow.State.CLOSED, "tessary/fix-case-0", 40, List.of(PROMPT.key()));
        FixPrRow newer = pr("pr-1", FixPrRow.State.CLOSED, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.listByCase("proj-1", "case-1")).thenReturn(List.of(newer, older));

        assertEquals(
                new Start.Stop(FixRunRow.Outcome.SKIPPED, FixRunRow.Reason.DECLINED, null),
                destination().start("proj-1", "case-1", List.of(RETRY, PROMPT)));
    }

    @Test
    void aFirstDeliveryCommitsOnTheBaseOpensABranchAndAReadyPullRequest() {
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        when(prs.insertOpen(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Integer.class),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenReturn("pr-new");
        git.commits.put(
                "base",
                new FakeGitProvider.Commit(
                        null, Map.of("src/agent.py", "blob-agent", "src/old.py", "blob-old"), "initial"));
        Start.Go go = new Start.Go(
                "proj-1", "case-1", new AgentSessionClient.Clone("url", "base", null), "main", List.of(RETRY), null);

        Delivery delivery = destination()
                .deliver(
                        go,
                        change(
                                "base",
                                List.of(RETRY.key()),
                                file("src/agent.py", "retry()"),
                                new PatchFile("src/old.py", null, null)));

        String branch = "tessary/fix-case-1-" + NOW.getEpochSecond();
        String head = Objects.requireNonNull(git.branches.get(branch));
        assertEquals(
                new Delivery.Delivered(
                        FixRunRow.Outcome.PR_OPENED, "pr-new", 41, "https://github.com/acme/agent/pull/41"),
                delivery);
        assertEquals(List.of(head, "base"), git.history(head));
        assertEquals(
                Map.of("src/agent.py", "retry()"),
                Map.of("src/agent.py", git.blobs.get(git.commit(head).files().get("src/agent.py"))));
        assertEquals(Set.of("src/agent.py"), git.commit(head).files().keySet());
        assertEquals("main", Objects.requireNonNull(git.prs.get(41)).baseBranch());
        assertEquals("Retry the search tool", git.titles.get(41));
        assertEquals("base", git.branches.get("main"));
        verify(prs)
                .insertOpen(
                        "proj-1",
                        "case-1",
                        branch,
                        41,
                        "https://github.com/acme/agent/pull/41",
                        head,
                        List.of(RETRY.key()),
                        NOW);
    }

    @Test
    void aLaterDeliveryAddsACommitOnTopOfThePullRequestBranchAndRewritesItsDescription() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        git.createPullRequest(INTEG, "tessary/fix-case-1", "main", "t", "old body");
        String prHead = git.humanPush("main", "src/agent.py");
        git.branches.put("main", "base");
        git.branches.put("tessary/fix-case-1", prHead);
        Start.Go go = new Start.Go(
                "proj-1",
                "case-1",
                AgentSessionClient.Clone.atBranch("url", "tessary/fix-case-1"),
                "tessary/fix-case-1",
                List.of(PROMPT),
                open);

        Delivery delivery = destination()
                .deliver(go, change(prHead, List.of(PROMPT.key()), file("prompts/system.md", "Answer in JSON.")));

        String head = Objects.requireNonNull(git.branches.get("tessary/fix-case-1"));
        assertEquals(
                new Delivery.Delivered(
                        FixRunRow.Outcome.COMMIT_ADDED, "pr-1", 41, "https://github.com/acme/agent/pull/41"),
                delivery);
        assertEquals(List.of(head, prHead, "base"), git.history(head));
        assertEquals("the body", git.bodies.get(41));
        verify(prs).recordCommit("pr-1", head, Set.of(RETRY.key(), PROMPT.key()), NOW);
    }

    @Test
    void aPersonsPushToOtherFilesIsKeptAndTheChangeIsRebuiltOnTopOfIt() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        git.createPullRequest(INTEG, "tessary/fix-case-1", "main", "t", "old body");
        git.branches.put("tessary/fix-case-1", "base");
        String human = git.humanPush("tessary/fix-case-1", "README.md");
        git.compares.put("base..." + human, List.of("README.md"));
        Start.Go go = new Start.Go(
                "proj-1",
                "case-1",
                AgentSessionClient.Clone.atBranch("url", "tessary/fix-case-1"),
                "tessary/fix-case-1",
                List.of(PROMPT),
                open);

        Delivery delivery = destination()
                .deliver(go, change("base", List.of(PROMPT.key()), file("prompts/system.md", "Answer in JSON.")));

        String head = Objects.requireNonNull(git.branches.get("tessary/fix-case-1"));
        assertInstanceOf(Delivery.Delivered.class, delivery);
        assertEquals(List.of(head, human, "base"), git.history(head));
        assertEquals(
                Set.of("src/agent.py", "README.md", "prompts/system.md"),
                git.commit(head).files().keySet());
    }

    @Test
    void aPersonsPushToTheSameFileSendsTheAgentBackRatherThanOverwritingIt() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        git.branches.put("tessary/fix-case-1", "base");
        String human = git.humanPush("tessary/fix-case-1", "prompts/system.md");
        git.compares.put("base..." + human, List.of("prompts/system.md"));
        Start.Go go = new Start.Go(
                "proj-1",
                "case-1",
                AgentSessionClient.Clone.atBranch("url", "tessary/fix-case-1"),
                "tessary/fix-case-1",
                List.of(PROMPT),
                open);

        Delivery delivery = destination()
                .deliver(go, change("base", List.of(PROMPT.key()), file("prompts/system.md", "Answer in JSON.")));

        assertEquals(new Delivery.Moved(), delivery);
        assertEquals(human, git.branches.get("tessary/fix-case-1"));
        verify(prs, never()).recordCommit(anyString(), anyString(), any(), any());
    }

    @Test
    void aPushTooLargeToCompareIsTreatedAsTouchingTheChange() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        git.branches.put("tessary/fix-case-1", "base");
        String human = git.humanPush("tessary/fix-case-1", "README.md");
        git.compares.put("base..." + human, Collections.nCopies(GithubPrDestination.COMPARE_CAP, "other.py"));
        Start.Go go = new Start.Go(
                "proj-1",
                "case-1",
                AgentSessionClient.Clone.atBranch("url", "tessary/fix-case-1"),
                "tessary/fix-case-1",
                List.of(PROMPT),
                open);

        Delivery delivery = destination()
                .deliver(go, change("base", List.of(PROMPT.key()), file("prompts/system.md", "Answer in JSON.")));

        assertEquals(new Delivery.Moved(), delivery);
        assertEquals(human, git.branches.get("tessary/fix-case-1"));
    }

    @Test
    void aPullRequestBranchSomeoneDeletedFailsTheDelivery() {
        FixPrRow open = pr("pr-1", FixPrRow.State.OPEN, "tessary/fix-case-1", 41, List.of(RETRY.key()));
        when(integrations.findByProject("proj-1")).thenReturn(Optional.of(INTEG));
        Start.Go go = new Start.Go(
                "proj-1",
                "case-1",
                AgentSessionClient.Clone.atBranch("url", "tessary/fix-case-1"),
                "tessary/fix-case-1",
                List.of(PROMPT),
                open);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> destination()
                        .deliver(go, change("base", List.of(PROMPT.key()), file("prompts/system.md", "JSON"))));

        assertEquals(GitError.BRANCH_MOVED, e.error());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.SessionEvent.PatchFile;
import ai.tessary.fix.FixRunRow.Outcome;
import ai.tessary.fix.FixRunRow.Reason;
import ai.tessary.git.GitCloneUrls;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitIntegrationService;
import ai.tessary.git.GitProviderClient;
import ai.tessary.git.GitProviderClient.PullRequest;
import ai.tessary.git.GitProviderClient.TreeEntry;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One ready-for-review pull request per case on the project's repository. A first run branches from the default
 * branch's head and opens it; a later run while it is open adds a commit on its branch, on top of any human commits,
 * and moves the branch only by fast-forward. Tessary never merges, closes or force-pushes. The pull request's state is
 * read from the provider at the start of every run: merged lets a later failure open a new one; closed without a merge
 * is a no to its causes, so they stay out of every later one.
 */
@Component
public class GithubPrDestination implements FixDestination {

    /** GitHub's compare lists at most this many files; at the cap, the overlap is unknown. */
    static final int COMPARE_CAP = 300;

    private final GitIntegrationRepository integrations;
    private final GitIntegrationService integrationService;
    private final GitProviderFactory providers;
    private final FixPrRepository prs;
    private final Clock clock;

    @Autowired
    public GithubPrDestination(
            GitIntegrationRepository integrations,
            GitIntegrationService integrationService,
            GitProviderFactory providers,
            FixPrRepository prs) {
        this(integrations, integrationService, providers, prs, Clock.systemUTC());
    }

    GithubPrDestination(
            GitIntegrationRepository integrations,
            GitIntegrationService integrationService,
            GitProviderFactory providers,
            FixPrRepository prs,
            Clock clock) {
        this.integrations = integrations;
        this.integrationService = integrationService;
        this.providers = providers;
        this.prs = prs;
        this.clock = clock;
    }

    @Override
    public @Nullable String blocked(String projectId) {
        Optional<GitIntegrationRow> integ = integrations.findByProject(projectId);
        if (integ.isEmpty()) return Reason.NO_REPO;
        return integrationService.writeAccess(integ.get()).canWrite() ? null : Reason.READ_ONLY;
    }

    @Override
    public Start start(String projectId, String caseId, List<FixableCause> causes) {
        Optional<GitIntegrationRow> found = integrations.findByProject(projectId);
        if (found.isEmpty()) return new Start.Stop(Outcome.SKIPPED, Reason.NO_REPO, null);
        GitIntegrationRow integ = found.get();
        GitProviderClient client = providers.client(integ.providerEnum());
        Set<String> declined = new HashSet<>();
        FixPrRow open = null;
        for (FixPrRow pr : prs.listByCase(projectId, caseId)) {
            if (FixPrRow.State.OPEN.equals(pr.state())) open = pr;
            if (FixPrRow.State.CLOSED.equals(pr.state())) declined.addAll(pr.causes());
        }
        String url = cloneUrl(integ);
        if (open != null) {
            PullRequest live = client.pullRequest(integ, open.prNumber());
            if (live.open()) {
                List<FixableCause> fresh = without(causes, declined, open.causes());
                if (fresh.isEmpty()) return new Start.Stop(Outcome.NO_CHANGE, null, open.id());
                return new Start.Go(
                        projectId,
                        caseId,
                        AgentSessionClient.Clone.atBranch(url, open.branch()),
                        open.branch(),
                        fresh,
                        open);
            }
            prs.markState(open.id(), live.merged() ? FixPrRow.State.MERGED : FixPrRow.State.CLOSED, clock.instant());
            if (live.rejected()) declined.addAll(open.causes());
        }
        List<FixableCause> remaining = without(causes, declined, List.of());
        if (remaining.isEmpty()) return new Start.Stop(Outcome.SKIPPED, Reason.DECLINED, null);
        String head = client.resolveHeadSha(integ, null);
        return new Start.Go(
                projectId,
                caseId,
                new AgentSessionClient.Clone(url, head, null),
                integ.defaultBranch(),
                remaining,
                null);
    }

    private static List<FixableCause> without(List<FixableCause> causes, Set<String> declined, List<String> addressed) {
        return causes.stream()
                .filter(c -> !declined.contains(c.key()) && !addressed.contains(c.key()))
                .toList();
    }

    /** Never the write token on an App connection: the clone token only reads contents. */
    private String cloneUrl(GitIntegrationRow integ) {
        return GitCloneUrls.authenticated(providers, integ)
                .orElseThrow(() -> new TessaryException(GitError.TOKEN_MINT_FAILED, integ.provider()));
    }

    @Override
    public Delivery deliver(Start.Go go, Change change) {
        GitIntegrationRow integ = integrations.findByProject(go.projectId()).orElseThrow();
        GitProviderClient client = providers.client(integ.providerEnum());
        List<TreeEntry> entries = new ArrayList<>();
        for (PatchFile f : change.files()) {
            entries.add(
                    f.deleted()
                            ? new TreeEntry(f.path(), "100644", null)
                            : new TreeEntry(f.path(), f.mode(), client.createBlob(integ, f.content())));
        }
        String commit = commit(client, integ, change, entries, change.baseSha());
        FixPrRow open = go.openPr();
        Instant now = clock.instant();
        if (open == null) {
            String branch = branchName(go.caseId(), now);
            client.createBranch(integ, branch, commit);
            PullRequest pr =
                    client.createPullRequest(integ, branch, integ.defaultBranch(), change.title(), change.body());
            String id = prs.insertOpen(
                    go.projectId(), go.caseId(), branch, pr.number(), pr.url(), commit, change.causes(), now);
            return new Delivery.Delivered(Outcome.PR_OPENED, id, pr.number(), pr.url());
        }
        try {
            client.updateBranch(integ, open.branch(), commit);
        } catch (TessaryException e) {
            if (!GitError.BRANCH_MOVED.equals(e.error())) throw e;
            Optional<String> rebuilt = rebuildOnMovedBranch(client, integ, open.branch(), change, entries);
            if (rebuilt.isEmpty()) return new Delivery.Moved();
            commit = rebuilt.get();
        }
        Set<String> all = new LinkedHashSet<>(open.causes());
        all.addAll(change.causes());
        prs.recordCommit(open.id(), commit, all, now);
        client.updatePullRequestBody(integ, open.prNumber(), change.body());
        return new Delivery.Delivered(Outcome.COMMIT_ADDED, open.id(), open.prNumber(), open.prUrl());
    }

    private static String commit(
            GitProviderClient client, GitIntegrationRow integ, Change change, List<TreeEntry> entries, String parent) {
        String tree = client.createTree(integ, client.commitTreeSha(integ, parent), entries);
        return client.createCommit(integ, change.commitMessage(), tree, parent);
    }

    /**
     * Someone pushed to the branch after the agent's clone. When their commits touch none of the change's files, the
     * change is rebuilt on the new head once; a second push in between still throws. Empty when they overlap, or when
     * there are too many to tell: the agent must see their commits.
     */
    private Optional<String> rebuildOnMovedBranch(
            GitProviderClient client, GitIntegrationRow integ, String branch, Change change, List<TreeEntry> entries) {
        String head = client.branchSha(integ, branch)
                .orElseThrow(() -> new TessaryException(GitError.BRANCH_MOVED, branch, integ.repoName()));
        List<String> touched = client.changedPaths(integ, change.baseSha(), head);
        Set<String> mine = new HashSet<>();
        for (TreeEntry e : entries) mine.add(e.path());
        if (touched.size() >= COMPARE_CAP || touched.stream().anyMatch(mine::contains)) return Optional.empty();
        String commit = commit(client, integ, change, entries, head);
        client.updateBranch(integ, branch, commit);
        return Optional.of(commit);
    }

    /** One fresh branch per pull request: a case that opens a second one after a merge never reuses a name. */
    static String branchName(String caseId, Instant now) {
        return "tessary/fix-" + caseId.toLowerCase(Locale.ROOT) + "-" + now.getEpochSecond();
    }
}

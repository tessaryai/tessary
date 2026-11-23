// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProvider;
import ai.tessary.git.GitProviderClient;
import ai.tessary.git.GitTokenService;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A repository in memory, as GitHub's API keeps one: commits with one parent and a tree, branches that move only by
 * fast-forward, and pull requests. A tree here is the map of path to blob it holds, so a test reads back exactly what a
 * commit carries.
 */
final class FakeGitProvider implements GitProviderClient, GitTokenService {

    static final String CLONE_TOKEN = "ghs_cloneOnly";

    record Commit(@Nullable String parent, Map<String, String> files, String message) {}

    final Map<String, Commit> commits = new HashMap<>();
    final Map<String, Map<String, String>> trees = new HashMap<>();
    final Map<String, String> blobs = new HashMap<>();
    final Map<String, String> branches = new LinkedHashMap<>();
    final Map<Integer, PullRequest> prs = new LinkedHashMap<>();
    final Map<Integer, String> bodies = new HashMap<>();
    final Map<Integer, String> titles = new HashMap<>();
    /** Paths a compare reports changed, by {@code base...head}; absent pairs report none. */
    final Map<String, List<String>> compares = new HashMap<>();

    private int seq;

    FakeGitProvider() {
        commits.put("base", new Commit(null, Map.of("src/agent.py", "blob-agent"), "initial"));
        branches.put("main", "base");
    }

    /** A person pushes a commit on top of {@code branch} that writes {@code path}. */
    String humanPush(String branch, String path) {
        String parent = Objects.requireNonNull(branches.get(branch));
        Map<String, String> files = new HashMap<>(commit(parent).files());
        files.put(path, "blob-human-" + (++seq));
        String sha = "human-" + seq;
        commits.put(sha, new Commit(parent, files, "a person's commit"));
        branches.put(branch, sha);
        return sha;
    }

    Commit commit(String sha) {
        return Objects.requireNonNull(commits.get(sha), sha);
    }

    @Override
    public GitProvider provider() {
        return GitProvider.GITHUB;
    }

    @Override
    public String authHeader(GitIntegrationRow integ) {
        return "Bearer ghs_writeToken";
    }

    @Override
    public String cloneAuthHeader(GitIntegrationRow integ) {
        return "Bearer " + CLONE_TOKEN;
    }

    @Override
    public RepoAccess verifyAccess(GitIntegrationRow integ) {
        return new RepoAccess("main");
    }

    @Override
    public String resolveHeadSha(GitIntegrationRow integ, @Nullable String branch) {
        return Objects.requireNonNull(branches.get(branch == null ? integ.defaultBranch() : branch));
    }

    @Override
    public String createBlob(GitIntegrationRow integ, byte[] content) {
        String sha = "blob-" + (++seq);
        blobs.put(sha, new String(content, java.nio.charset.StandardCharsets.UTF_8));
        return sha;
    }

    @Override
    public String commitTreeSha(GitIntegrationRow integ, String commitSha) {
        String tree = "tree-of-" + commitSha;
        trees.putIfAbsent(tree, commit(commitSha).files());
        return tree;
    }

    @Override
    public String createTree(GitIntegrationRow integ, String baseTreeSha, List<TreeEntry> entries) {
        Map<String, String> files = new HashMap<>(Objects.requireNonNull(trees.get(baseTreeSha)));
        for (TreeEntry e : entries) {
            if (e.blobSha() == null) files.remove(e.path());
            else files.put(e.path(), e.blobSha());
        }
        String sha = "tree-" + (++seq);
        trees.put(sha, files);
        return sha;
    }

    @Override
    public String createCommit(GitIntegrationRow integ, String message, String treeSha, String parentSha) {
        String sha = "commit-" + (++seq);
        commits.put(sha, new Commit(parentSha, Objects.requireNonNull(trees.get(treeSha)), message));
        return sha;
    }

    @Override
    public Optional<String> branchSha(GitIntegrationRow integ, String branch) {
        return Optional.ofNullable(branches.get(branch));
    }

    @Override
    public void createBranch(GitIntegrationRow integ, String branch, String sha) {
        if (branches.containsKey(branch)) throw new TessaryException(GitError.BRANCH_MOVED, branch, "acme/agent");
        branches.put(branch, sha);
    }

    @Override
    public void updateBranch(GitIntegrationRow integ, String branch, String sha) {
        String head = branches.get(branch);
        for (String at = sha; at != null; at = commit(at).parent()) {
            if (at.equals(head)) {
                branches.put(branch, sha);
                return;
            }
        }
        throw new TessaryException(GitError.BRANCH_MOVED, branch, "acme/agent");
    }

    @Override
    public List<String> changedPaths(GitIntegrationRow integ, String baseSha, String headSha) {
        return compares.getOrDefault(baseSha + "..." + headSha, List.of());
    }

    @Override
    public PullRequest createPullRequest(GitIntegrationRow integ, String head, String base, String title, String body) {
        int number = 40 + prs.size() + 1;
        PullRequest pr = new PullRequest(
                number, "https://github.com/acme/agent/pull/" + number, "open", false, head, branches.get(head), base);
        prs.put(number, pr);
        bodies.put(number, body);
        titles.put(number, title);
        return pr;
    }

    @Override
    public PullRequest pullRequest(GitIntegrationRow integ, int number) {
        PullRequest pr = prs.get(number);
        if (pr == null) throw new TessaryException(GitError.PR_NOT_FOUND, number, "acme/agent");
        return pr;
    }

    @Override
    public void updatePullRequestBody(GitIntegrationRow integ, int number, String body) {
        bodies.put(number, body);
    }

    /** A pull request a reviewer merged or closed. */
    void close(int number, boolean merged) {
        PullRequest pr = Objects.requireNonNull(prs.get(number));
        prs.put(
                number,
                new PullRequest(number, pr.url(), "closed", merged, pr.headBranch(), pr.headSha(), pr.baseBranch()));
    }

    /** The commits from {@code sha} back to the root, newest first. */
    List<String> history(String sha) {
        List<String> out = new ArrayList<>();
        for (String at = sha; at != null; at = commit(at).parent()) out.add(at);
        return out;
    }
}

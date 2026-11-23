// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The write defaults a provider without write support keeps. */
class GitProviderClientTest {

    /** A provider that only reads, as every provider but GitHub does today. */
    private static final GitProviderClient READ_ONLY = new GitProviderClient() {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public RepoAccess verifyAccess(GitIntegrationRow integ) {
            return new RepoAccess("main");
        }

        @Override
        public String resolveHeadSha(GitIntegrationRow integ, String branch) {
            return "abc";
        }
    };

    private static final GitIntegrationRow REPO =
            new GitIntegrationRow("i1", "p1", "github", "api.github.com", "acme", "web", "main", "enc", "t", "t");

    /**
     * Every write on a provider that cannot write refuses with UNSUPPORTED_PROVIDER. Bug caught: a default that returns
     * a value or does nothing, so the fix step reports a branch or pull request it never made.
     */
    @Test
    void everyWriteOnAProviderWithoutWriteSupportRefuses() {
        List<Consumer<GitProviderClient>> writes = List.of(
                c -> c.createBlob(REPO, new byte[0]),
                c -> c.commitTreeSha(REPO, "c1"),
                c -> c.createTree(REPO, "t1", List.of(new GitProviderClient.TreeEntry("a.py", "100644", null))),
                c -> c.createCommit(REPO, "msg", "t1", "c1"),
                c -> c.branchSha(REPO, "fix/x"),
                c -> c.createBranch(REPO, "fix/x", "c1"),
                c -> c.updateBranch(REPO, "fix/x", "c2"),
                c -> c.changedPaths(REPO, "c1", "c2"),
                c -> c.createPullRequest(REPO, "fix/x", "main", "title", "body"),
                c -> c.findPullRequest(REPO, "fix/x"),
                c -> c.pullRequest(REPO, 7),
                c -> c.updatePullRequestBody(REPO, 7, "body"));

        assertAll(writes.stream().map(write -> (Executable) () -> {
            TessaryException e = assertThrows(TessaryException.class, () -> write.accept(READ_ONLY));
            assertEquals(GitError.UNSUPPORTED_PROVIDER, e.error());
        }));
    }
}

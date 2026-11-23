// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes one connected repository. The write calls build a commit from blobs and a tree, move a branch only
 * by fast-forward (never force), and open or update a pull request. A provider that cannot write keeps the defaults,
 * which refuse.
 */
public interface GitProviderClient {

    GitProvider provider();

    /**
     * Called once at connect time, before anything is stored, so a typo'd or under-scoped token fails visibly instead
     * of silently at the first RCA. Implementations must distinguish
     * {@link ai.tessary.open.errors.GitError#CREDENTIALS_REJECTED},
     * {@link ai.tessary.open.errors.GitError#REPO_ACCESS_DENIED} and
     * {@link ai.tessary.open.errors.GitError#REPO_UNREACHABLE} (the provider won't say whether the repo is absent or
     * invisible).
     */
    RepoAccess verifyAccess(GitIntegrationRow integ);

    record RepoAccess(String defaultBranch) {}

    /** The integration's default branch when {@code branch} is null. */
    String resolveHeadSha(GitIntegrationRow integ, String branch);

    // ---- write access ----------------------------------------------------------------------------

    /** Why a connection cannot write. */
    enum WriteBlock {
        /** The GitHub App lacks Contents: write or Pull requests: write, or the installation has not accepted it. */
        APP_PERMISSION_MISSING,
        /** The access token cannot push to the repository. */
        TOKEN_CANNOT_PUSH,
        /** The provider could not be asked. */
        CHECK_FAILED,
        /** This provider has no write support in Tessary. */
        UNSUPPORTED
    }

    /** {@code reason} is null exactly when {@code canWrite}. */
    record WriteAccess(boolean canWrite, @Nullable WriteBlock reason) {
        public static WriteAccess granted() {
            return new WriteAccess(true, null);
        }

        public static WriteAccess blocked(WriteBlock reason) {
            return new WriteAccess(false, reason);
        }
    }

    /**
     * Whether Tessary can push a branch and open a pull request. Best effort: a write call that is refused still
     * fails with {@link GitError#WRITE_ACCESS_MISSING}.
     */
    default WriteAccess writeAccess(GitIntegrationRow integ) {
        return WriteAccess.blocked(WriteBlock.UNSUPPORTED);
    }

    // ---- commits and refs ------------------------------------------------------------------------

    /** One path in a new tree. A null {@code blobSha} deletes the path. {@code mode} is e.g. {@code 100644}. */
    record TreeEntry(String path, String mode, @Nullable String blobSha) {}

    /** Stores the bytes and returns the blob sha. */
    default String createBlob(GitIntegrationRow integ, byte[] content) {
        throw unsupported();
    }

    /** The tree sha of a commit, to build a new tree on. */
    default String commitTreeSha(GitIntegrationRow integ, String commitSha) {
        throw unsupported();
    }

    /** A tree that is {@code baseTreeSha} with {@code entries} written over it. Returns the new tree sha. */
    default String createTree(GitIntegrationRow integ, String baseTreeSha, List<TreeEntry> entries) {
        throw unsupported();
    }

    /** A commit with one parent. Returns the new commit sha. */
    default String createCommit(GitIntegrationRow integ, String message, String treeSha, String parentSha) {
        throw unsupported();
    }

    /** The commit a branch points at, or empty when there is no such branch. */
    default Optional<String> branchSha(GitIntegrationRow integ, String branch) {
        throw unsupported();
    }

    /** Creates a branch. {@link GitError#BRANCH_MOVED} when it already exists. */
    default void createBranch(GitIntegrationRow integ, String branch, String sha) {
        throw unsupported();
    }

    /** Moves a branch by fast-forward only. {@link GitError#BRANCH_MOVED} when someone pushed first. */
    default void updateBranch(GitIntegrationRow integ, String branch, String sha) {
        throw unsupported();
    }

    /**
     * The paths that changed from {@code baseSha} to {@code headSha}, renames under both names. The provider may cap
     * the list (GitHub stops at 300 files).
     */
    default List<String> changedPaths(GitIntegrationRow integ, String baseSha, String headSha) {
        throw unsupported();
    }

    // ---- pull requests ---------------------------------------------------------------------------

    /** {@code state} is {@code open} or {@code closed}; a merged pull request is closed with {@code merged} true. */
    record PullRequest(
            int number,
            String url,
            String state,
            boolean merged,
            String headBranch,
            String headSha,
            String baseBranch) {
        public boolean open() {
            return "open".equals(state);
        }

        /** Closed without a merge: the reviewer said no. */
        public boolean rejected() {
            return !open() && !merged;
        }
    }

    /** Opens a ready-for-review (not draft) pull request from {@code head} into {@code base}. */
    default PullRequest createPullRequest(
            GitIntegrationRow integ, String head, String base, String title, String body) {
        throw unsupported();
    }

    /** The newest pull request from {@code head} in this repository, in any state. */
    default Optional<PullRequest> findPullRequest(GitIntegrationRow integ, String head) {
        throw unsupported();
    }

    /** {@link GitError#PR_NOT_FOUND} when there is no such pull request. */
    default PullRequest pullRequest(GitIntegrationRow integ, int number) {
        throw unsupported();
    }

    /** Replaces the description. {@link GitError#PR_NOT_FOUND} when there is no such pull request. */
    default void updatePullRequestBody(GitIntegrationRow integ, int number, String body) {
        throw unsupported();
    }

    private TessaryException unsupported() {
        return new TessaryException(GitError.UNSUPPORTED_PROVIDER, provider().wire());
    }
}

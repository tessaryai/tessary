// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.List;

/**
 * Read + write operations the observer needs against a hosted repo, abstracted
 * across providers. Reads back the diff and bundle tree; writes only ever open a
 * change-request (PR/MR) — never a push to the default branch.
 */
public interface GitProviderClient {

    GitProvider provider();

    /**
     * Confirm the integration's credentials can actually read the repo, and report what the provider
     * knows about it. Called once at connect time, before anything is stored: without it a typo'd or
     * under-scoped token binds cleanly and then fails silently at the first RCA, which reads as
     * "Tessary ignored my repo" rather than as a credential the reader can fix.
     *
     * <p>Implementations must distinguish the failures a person recovers from differently:
     * {@link ai.tessary.open.errors.GitError#CREDENTIALS_REJECTED} for a bad or expired credential,
     * {@link ai.tessary.open.errors.GitError#REPO_ACCESS_DENIED} for an explicit refusal, and
     * {@link ai.tessary.open.errors.GitError#REPO_UNREACHABLE} where the provider will not say
     * whether the repo is absent or merely invisible.
     */
    RepoAccess verifyAccess(GitIntegrationRow integ);

    /** What a provider tells us about a repo we can read. */
    record RepoAccess(String defaultBranch) {}

    /** Current head SHA of {@code branch} (or the integration's default branch when null). */
    String resolveHeadSha(GitIntegrationRow integ, String branch);

    /** Files changed between two commits, coalesced into one window. */
    CommitComparison compare(GitIntegrationRow integ, String baseSha, String headSha);

    /** True when {@code ancestorSha} is an ancestor of (or equal to) {@code descendantSha}. */
    boolean isAncestor(GitIntegrationRow integ, String ancestorSha, String descendantSha);

    /**
     * Whether the bundle dir (the top-level component of {@code pathPrefix}, e.g. {@code .tessary})
     * differs between two commits — used as a definitive fallback when {@link #compare} truncated its
     * file list and {@code bundleTouched} can't be trusted. Default is conservative (assume changed);
     * providers that can resolve a subtree sha cheaply should override.
     */
    default boolean bundleDirChanged(GitIntegrationRow integ, String baseSha, String headSha, String pathPrefix) {
        return true;
    }

    /**
     * Whether the bundle dir (the top-level component of {@code pathPrefix}) exists at {@code sha}
     * — the observer's bootstrap gate. Default is conservative for that caller (assume present, so
     * a provider without a cheap probe never triggers a repo-wide bootstrap analysis spuriously);
     * providers that can resolve a subtree sha cheaply should override.
     */
    default boolean bundleDirExists(GitIntegrationRow integ, String sha, String pathPrefix) {
        return true;
    }

    /** All files under {@code pathPrefix} at {@code sha}, with decoded text content. */
    List<RepoFile> getTreeFiles(GitIntegrationRow integ, String sha, String pathPrefix);

    /** Open a change-request carrying the given file edits on a new branch off {@code baseBranch}. */
    ChangeRequest openChangeRequest(GitIntegrationRow integ, ChangeRequestSpec spec);

    record ChangedFile(String path, String status) {}

    /**
     * @param status GitHub-style relation of base→head: {@code ahead}/{@code identical} mean base is
     *     an ancestor of head (the diff is meaningful); {@code behind}/{@code diverged} mean it is not.
     */
    record CommitComparison(String baseSha, String headSha, int aheadBy, String status, List<ChangedFile> files) {}

    record RepoFile(String path, String content) {}

    record FileChange(String path, String content) {}

    /**
     * @param draft open the PR as a draft
     * @param rolling reuse an existing {@code headBranch}/open PR — commit on top of the branch
     *     (accumulate) and update the PR, rather than failing or opening a duplicate
     */
    record ChangeRequestSpec(
            String headBranch,
            String baseBranch,
            String title,
            String body,
            List<FileChange> files,
            boolean draft,
            boolean rolling) {}

    record ChangeRequest(String url, int number) {}
}

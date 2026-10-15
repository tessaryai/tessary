// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

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
}

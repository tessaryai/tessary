// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

/**
 * Read-only operations against a hosted repo, abstracted across providers: verify
 * the repo is readable at connect time, and resolve a branch head SHA for RCA.
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
}

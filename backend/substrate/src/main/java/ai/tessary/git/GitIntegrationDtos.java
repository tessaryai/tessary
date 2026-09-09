// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** HTTP-shaped DTOs for /api/.../git. */
public final class GitIntegrationDtos {

    private GitIntegrationDtos() {}

    /**
     * Bind a project to a repo. For GitHub, {@code installationId} identifies the
     * App installation (the platform mints tokens from its own private key);
     * token-based providers would instead carry a sealed {@code token}.
     */
    public record ConnectRequest(
            @NotBlank String provider,
            @NotBlank String repoOwner,
            @NotBlank String repoName,
            String host,
            String defaultBranch,
            Long installationId,
            String token) {}

    public record GitIntegrationView(
            String provider,
            String host,
            String repoOwner,
            String repoName,
            String defaultBranch,
            String observerCursorSha) {
        public static GitIntegrationView from(GitIntegrationRow r) {
            return new GitIntegrationView(
                    r.provider(), r.host(), r.repoOwner(), r.repoName(), r.defaultBranch(), r.observerCursorSha());
        }
    }

    public record DeleteResponse(boolean deleted) {}

    /** The GitHub App install (or OAuth authorize) URL the frontend redirects the browser to. */
    public record InstallUrlView(String url) {}

    /**
     * The GitHub App manifest-flow starting point: {@code manifest} is the
     * JSON blob the frontend auto-submits as a POSTed {@code manifest} form field to {@code url}
     * (github.com's manifest-flow needs POST, not a redirect — the manifest is too large for a
     * query string, unlike the install/authorize URLs above).
     */
    public record ManifestStartView(String url, String manifest) {}

    /**
     * One repo the authorizing user can bind, surfaced in the "connect an existing installation"
     * picker. {@code installationId} ties the repo back to the App installation that grants it.
     */
    public record RepoOption(long installationId, String owner, String name, String defaultBranch) {}

    /** The repos a user can pick from when reusing an installation already on their account/org. */
    public record InstallationOptionsView(List<RepoOption> candidates) {}

    /**
     * Finalize a "connect an existing installation" pick. {@code token} is the SecretBox-sealed
     * selection blob the callback minted (carrying the verified-administered installation ids); the
     * backend re-validates the chosen {@code installationId}/{@code repoOwner}/{@code repoName}
     * against it, so the picker can't bind an installation the user doesn't administer.
     */
    public record SelectInstallationRequest(
            @NotBlank String token,
            long installationId,
            @NotBlank String repoOwner,
            @NotBlank String repoName) {}
}

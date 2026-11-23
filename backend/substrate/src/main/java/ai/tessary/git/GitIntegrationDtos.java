// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import ai.tessary.git.GitProviderClient.WriteAccess;
import ai.tessary.git.GitProviderClient.WriteBlock;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class GitIntegrationDtos {

    private GitIntegrationDtos() {}

    /**
     * For GitHub, {@code installationId} identifies the App installation; token-based providers would carry a sealed
     * {@code token}.
     */
    public record ConnectRequest(
            @NotBlank String provider,
            @NotBlank String repoOwner,
            @NotBlank String repoName,
            String host,
            String defaultBranch,
            Long installationId,
            String token) {}

    /** {@code writeReason} says why Tessary cannot open pull requests; null when {@code canWrite}. */
    public record GitIntegrationView(
            String provider,
            String host,
            String repoOwner,
            String repoName,
            String defaultBranch,
            boolean canWrite,
            @Nullable WriteBlock writeReason) {
        public static GitIntegrationView from(GitIntegrationRow r, WriteAccess write) {
            return new GitIntegrationView(
                    r.provider(),
                    r.host(),
                    r.repoOwner(),
                    r.repoName(),
                    r.defaultBranch(),
                    write.canWrite(),
                    write.reason());
        }
    }

    public record DeleteResponse(boolean deleted) {}

    public record InstallUrlView(String url) {}

    /** The manifest is POSTed as a form field: it is too large for a query string. */
    public record ManifestStartView(String url, String manifest) {}

    public record RepoOption(long installationId, String owner, String name, String defaultBranch) {}

    public record InstallationOptionsView(List<RepoOption> candidates) {}

    /**
     * {@code token} is the sealed selection blob; the backend re-validates the pick against it, so the picker can't
     * bind an installation the user doesn't administer.
     */
    public record SelectInstallationRequest(
            @NotBlank String token,
            long installationId,
            @NotBlank String repoOwner,
            @NotBlank String repoName) {}
}

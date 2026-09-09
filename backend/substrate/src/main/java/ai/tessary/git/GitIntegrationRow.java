// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

/**
 * A project's binding to one repo on one git provider. {@code credentialsEnc}
 * holds SecretBox-sealed, provider-specific JSON (for GitHub: the App
 * installation id). One integration per project (v1).
 */
public record GitIntegrationRow(
        String id,
        String projectId,
        String provider,
        String host,
        String repoOwner,
        String repoName,
        String defaultBranch,
        String credentialsEnc,
        String observerCursorSha,
        String createdAt,
        String updatedAt) {
    public GitProvider providerEnum() {
        return GitProvider.fromWire(provider);
    }
}

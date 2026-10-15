// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

/**
 * {@code credentialsEnc} is SecretBox-sealed provider-specific JSON (for GitHub, the installation id). One integration
 * per project.
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
        String createdAt,
        String updatedAt) {
    public GitProvider providerEnum() {
        return GitProvider.fromWire(provider);
    }
}

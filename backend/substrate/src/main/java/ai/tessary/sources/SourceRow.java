// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

/** {@code credentials_enc} is sealed. */
public record SourceRow(
        String id,
        String projectId,
        String provider,
        String name,
        String baseUrl,
        String credentialsEnc,
        String createdAt,
        String updatedAt) {}

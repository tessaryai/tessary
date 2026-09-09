// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ServiceTier;
import dev.langchain4j.model.bedrock.BedrockServiceTier;
import org.jspecify.annotations.Nullable;

/**
 * Maps the platform's {@link ServiceTier} onto Bedrock's own enum.
 *
 * <p>Lives here rather than on the enum because {@code ServiceTier} is persisted, priced and reported
 * on by layers that never call a provider — {@code usage} meters it, {@code judge} selects it — and
 * carrying a {@code BedrockServiceTier} field made every one of them depend on a model SDK to read a
 * string and a price factor.
 *
 * <p>A second provider is a second method here, not a second field on the enum.
 */
public final class BedrockTiers {

    private BedrockTiers() {}

    /** The Bedrock wire value, or null for a tier Bedrock has no concept of (it is then omitted). */
    public static @Nullable BedrockServiceTier wireOf(@Nullable ServiceTier tier) {
        if (tier == null) return null;
        return switch (tier) {
            case STANDARD -> BedrockServiceTier.DEFAULT;
            case FLEX -> BedrockServiceTier.FLEX;
            case PRIORITY -> BedrockServiceTier.PRIORITY;
            default -> null;
        };
    }
}

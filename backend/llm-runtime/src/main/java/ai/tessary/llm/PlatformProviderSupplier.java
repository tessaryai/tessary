// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whether the deployment offers {@link ModelProvider#PLATFORM} to an org, and how to show it.
 *
 * <p>The default, registered by {@link LlmSeamConfig} only when no other build supplies one, offers
 * it to no org: this build has no platform-held credential to run it on, so the honest answer is
 * that the provider does not exist here. Another build registers its own supplier to offer it.
 *
 * <p>Tessary Cloud registers one to offer its Tessary AI provider. Nothing in this repo offers
 * {@link ModelProvider#PLATFORM}, so this seam has no caller here by design: keep it, it is not dead
 * code.
 */
public interface PlatformProviderSupplier {

    /**
     * Whether {@code orgId} can run on {@link ModelProvider#PLATFORM}. On the path of every sandbox
     * run and decision call (it feeds {@code ProjectModelSettings#resolve}), so an implementation
     * must answer from memory or configuration, not a query per call.
     */
    boolean available(String orgId);

    /**
     * The Providers page's entry for {@link ModelProvider#PLATFORM}, or empty when the org cannot run
     * it. Read only by the settings endpoints, so it may do real work.
     */
    Optional<SuppliedProvider> describe(String orgId);

    /**
     * @param label the provider's name on the Providers page
     * @param detail a short status line under it, such as a remaining balance, or null for none
     */
    record SuppliedProvider(String label, @JsonProperty("detail") @Nullable String detail) {}

    /** The open default: {@link ModelProvider#PLATFORM} is offered to no org. */
    static PlatformProviderSupplier none() {
        return new PlatformProviderSupplier() {
            @Override
            public boolean available(String orgId) {
                return false;
            }

            @Override
            public Optional<SuppliedProvider> describe(String orgId) {
                return Optional.empty();
            }
        };
    }
}

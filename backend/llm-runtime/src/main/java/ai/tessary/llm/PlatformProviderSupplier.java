// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whether the deployment offers {@link ModelProvider#PLATFORM} to an org. The default, registered by
 * {@link LlmSeamConfig}, offers it to no one. Another build registers its own; the seam has no caller in this repo by
 * design, so it is not dead code.
 */
public interface PlatformProviderSupplier {

    /** On the path of every sandbox run and decision call, so answer from memory or configuration, not a query. */
    boolean available(String orgId);

    /** Read only by the settings endpoints, so it may do real work. */
    Optional<SuppliedProvider> describe(String orgId);

    /** @param detail a short status line, such as a remaining balance, or null */
    record SuppliedProvider(
            String label, @JsonProperty("detail") @Nullable String detail) {}

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

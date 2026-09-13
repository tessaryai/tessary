// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The pricing switch, bound from {@code tessary.pricing.*}. Governs {@code pricing/PriceBookImporter} (the
 * bundled book, on boot) and {@code pricing/PriceBookFetcher} (home.tessary.ai's book, on the telemetry tick).
 *
 * <p>There is deliberately nothing here about the rates themselves — no paths, no overrides, no
 * per-environment prices. Rates are checked-in files imported into versioned rows, so a price change
 * arrives as a reviewed diff rather than as configuration somebody can differ between deployments.
 */
@Component
@ConfigurationProperties(prefix = "tessary.pricing")
public class PricingProperties {

    /** Master switch. Off means no book is ever imported or fetched, so every model prices as unpriced. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}

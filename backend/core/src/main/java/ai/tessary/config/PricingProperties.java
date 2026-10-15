// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The pricing switch ({@code tessary.pricing.*}) for the bundled and fetched price books. Rates are deliberately not
 * configurable: they are checked-in files, so a price change is a reviewed diff.
 */
@Component
@ConfigurationProperties(prefix = "tessary.pricing")
public class PricingProperties {

    /** Off means every model prices as unpriced. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The rate-import job's switches, bound from {@code tessary.pricing.*}. Governs
 * {@code pricing/PriceBookImporter}.
 *
 * <p>There is deliberately nothing here about the rates themselves — no paths, no overrides, no
 * per-environment prices. Rates are checked-in files imported into versioned rows, so a price change
 * arrives as a reviewed diff rather than as configuration somebody can differ between deployments.
 */
@Component
@ConfigurationProperties(prefix = "tessary.pricing")
public class PricingProperties {

    /** Master switch. Off means no book is ever imported, so every model prices as unpriced. */
    private boolean enabled = true;

    /**
     * How often the import re-checks the checked-in snapshots, in milliseconds. Daily: a deploy is the
     * usual way a new snapshot arrives, and this only covers an instance up longer than the release
     * cadence. The value is read by the {@code @Scheduled} annotation directly, which cannot see a bean —
     * it is declared here so the key has one documented home.
     */
    private long importIntervalMs = 86_400_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public long getImportIntervalMs() {
        return importIntervalMs;
    }

    public void setImportIntervalMs(long v) {
        this.importIntervalMs = v;
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The opt-out switch for the {@code home.tessary.ai} telemetry ping ({@code TESSARY_TELEMETRY_ENABLED}). Check
 * {@link #enabled} before touching {@link InstanceIdRepository} or {@link HomeTessaryClient} at all: the contract
 * promises zero outbound calls, DNS included, when disabled.
 */
@Component
@ConfigurationProperties(prefix = "tessary.telemetry")
public class TelemetryProperties {

    /** When false, nothing in this package runs. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

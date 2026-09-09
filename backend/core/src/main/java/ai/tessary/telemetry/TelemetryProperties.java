// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The single opt-out switch for the {@code home.tessary.ai} telemetry ping, bound from
 * {@code tessary.telemetry.enabled} / {@code TESSARY_TELEMETRY_ENABLED} (Spring relaxed binding).
 *
 * <p>Replaces {@code tessary.analytics.*} (deleted with the Mixpanel stack). There is no token
 * field here — unlike Mixpanel, {@code devdocs/reference/telemetry-contract.md} carries no third-party
 * credential, so there is nothing else for this class to hold.
 *
 * <p><b>On by default (opt-out).</b> {@link #enabled} is the ONE gate the contract's §3
 * anonymity guarantee depends on: a caller must check it before touching {@link InstallIdRepository} or
 * {@link HomeTessaryClient} at all, not merely before sending — reading or minting an install id when
 * telemetry is off is itself an observable side effect the contract's "zero outbound calls, including
 * DNS resolution, when disabled" language forecloses.
 */
@Component
@ConfigurationProperties(prefix = "tessary.telemetry")
public class TelemetryProperties {

    /** Master switch (on by default). When false, nothing in this package runs. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

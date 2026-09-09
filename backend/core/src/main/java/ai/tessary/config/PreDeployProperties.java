// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the production-signal → pre-deploy feedback loop, bound from
 * {@code tessary.predeploy.*}. {@code enabled} gates BOTH halves of the loop and defaults off (opt-in),
 * mirroring {@link ClassifierProperties}: the write half (the {@code ClassifierWorker} sweep registering a
 * {@code pre_deploy_check} on a newly-discovered signal) and the read half (a future PR's risk
 * forecast unioning the registered surfaces in). When off, neither path touches the new table, so the
 * routing / prediction / CI forecast outputs are byte-for-byte unchanged.
 *
 * <p>Defaults live here in code (no yaml entries needed), mirroring {@link ClassifierProperties} /
 * {@link RiskModelProperties}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.predeploy")
public class PreDeployProperties {

    /** Opt-in master switch for the whole feedback loop (write + read). Default off. */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}

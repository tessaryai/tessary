// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the production-signal → pre-deploy feedback loop, bound from
 * {@code tessary.predeploy.*}. {@code enabled} gates the loop and defaults off (opt-in), mirroring
 * {@link ClassifierProperties}: the {@code ClassifierWorker} sweep registering a
 * {@code pre_deploy_check} on a newly-discovered signal. When off, the sweep never touches the table.
 *
 * <p>Defaults live here in code (no yaml entries needed), mirroring {@link ClassifierProperties}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.predeploy")
public class PreDeployProperties {

    /** Opt-in master switch for the feedback loop. Default off. */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}

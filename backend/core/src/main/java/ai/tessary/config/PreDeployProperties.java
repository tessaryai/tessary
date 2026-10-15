// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The production-signal to pre-deploy feedback loop ({@code tessary.predeploy.*}). Off by default; when off, the sweep
 * never touches {@code pre_deploy_check}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.predeploy")
public class PreDeployProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}

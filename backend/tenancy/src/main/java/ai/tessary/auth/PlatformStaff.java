// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.config.PlatformStaffProperties;
import org.springframework.stereotype.Service;

/**
 * The platform-staff gate, a second authority beside {@link ai.tessary.tenant.rbac.RolePermissions} for non-public
 * {@code /actuator/**}. Identity comes from deployment config and the sealed session. Bearer keys never qualify: a
 * staff-created key in a customer project would otherwise carry staff authority there.
 */
@Service
public class PlatformStaff {

    private final PlatformStaffProperties props;

    public PlatformStaff(PlatformStaffProperties props) {
        this.props = props;
    }

    /** Identity only; no org standing implied. */
    public boolean isStaff(TenantContext ctx) {
        return !ctx.isMcpToken() && props.isStaffEmail(ctx.userEmail());
    }
}

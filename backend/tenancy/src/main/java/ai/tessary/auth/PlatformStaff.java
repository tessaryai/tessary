// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.config.PlatformStaffProperties;
import org.springframework.stereotype.Service;

/**
 * The platform-staff gate: a second authority alongside {@link ai.tessary.tenant.rbac.RolePermissions},
 * for the few things that belong to us rather than to the customer. Today that is the non-public
 * {@code /actuator/**} paths, which {@link AuthFilter} opens to a signed-in staff session.
 *
 * <p>Staff identity comes from deployment config ({@link PlatformStaffProperties}), where a customer cannot
 * reach it, and is proven by the sealed session cookie.
 *
 * <p>Bearer/MCP key contexts never qualify. A key carries its creator's identity, so a staff-created key
 * scoped to a customer project would otherwise carry staff authority into that project.
 */
@Service
public class PlatformStaff {

    private final PlatformStaffProperties props;

    public PlatformStaff(PlatformStaffProperties props) {
        this.props = props;
    }

    /** Whether this request is a signed-in platform-staff session (identity only, no org standing implied). */
    public boolean isStaff(TenantContext ctx) {
        return !ctx.isMcpToken() && props.isStaffEmail(ctx.userEmail());
    }
}

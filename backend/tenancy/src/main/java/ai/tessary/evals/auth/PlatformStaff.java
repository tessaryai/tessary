// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import ai.tessary.evals.auth.TenantPathResolver.OrgResolved;
import ai.tessary.evals.config.PlatformStaffProperties;
import ai.tessary.evals.tenant.rbac.Role;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The platform-staff gate — a second authority alongside {@link ai.tessary.evals.tenant.rbac.RolePermissions},
 * for the handful of actions that belong to us rather than to the customer. Today that is exactly one thing:
 * moving an org's plan by hand, so a pilot can be put on a paid tier without a self-serve purchase.
 *
 * <p>It is deliberately NOT a {@link Role}. A role is something an org grants inside its own tenancy, and every
 * user is owner of their own org — so any role-shaped answer to "may I change my plan?" is yes for everyone,
 * which is the hole this replaces. Staff identity comes from deployment config instead, where a customer
 * cannot reach it.
 *
 * <h2>Both halves are required</h2>
 * {@link #canAdminister} is staff identity AND owner/admin standing in the target org. The identity half is
 * what makes it safe: the caller must actually be a staff member, proven by the sealed session cookie, so a
 * customer inviting staff into their org gains nothing. The standing half is consent and blast radius —
 * staff cannot move the plan of an org they were added to in passing, and the access is visible to the
 * customer in their own member list, revocable by removing the membership.
 *
 * <p>Bearer/MCP key contexts never qualify. A key carries its creator's identity, so a staff-created key
 * scoped to a customer project would otherwise carry staff authority into that project.
 */
@Service
public class PlatformStaff {

    /**
     * The standing staff must hold in the target org. ADMIN is the role a pilot is asked to grant; OWNER is
     * included so staff administering our own org is not a special case.
     */
    private static final Set<Role> ADMINISTRATIVE_ROLES = EnumSet.of(Role.OWNER, Role.ADMIN);

    private final PlatformStaffProperties props;

    public PlatformStaff(PlatformStaffProperties props) {
        this.props = props;
    }

    /** Whether this request is a signed-in platform-staff session (identity only, no org standing implied). */
    public boolean isStaff(TenantContext ctx) {
        return !ctx.isMcpToken() && props.isStaffEmail(ctx.userEmail());
    }

    /** Whether this request may administer {@code org}: staff identity AND owner/admin standing in it. */
    public boolean canAdminister(TenantContext ctx, OrgResolved org) {
        return isStaff(ctx) && ADMINISTRATIVE_ROLES.contains(org.roleEnum());
    }

    /**
     * Throw 403 unless {@link #canAdminister}. The message distinguishes the two failure halves, because
     * "you are not staff" and "you are staff but only a member here" need different fixes.
     */
    public void requireAdminister(TenantContext ctx, OrgResolved org, String action) {
        if (isStaff(ctx)) {
            if (!ADMINISTRATIVE_ROLES.contains(org.roleEnum())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "platform staff must hold the owner or admin role in this organization to " + action);
            }
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform staff only: " + action);
    }
}

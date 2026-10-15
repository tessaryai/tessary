// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.tenant.rbac.RolePermissions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolves org and project slugs into IDs while enforcing org membership, so no controller re-implements it. */
@Service
public class TenantPathResolver {

    private final OrganizationRepository orgs;
    private final OrgMembershipRepository memberships;
    private final ProjectRepository projects;

    public TenantPathResolver(
            OrganizationRepository orgs, OrgMembershipRepository memberships, ProjectRepository projects) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.projects = projects;
    }

    /** 403 on no membership. */
    public OrgResolved requireOrg(TenantContext ctx, String orgSlug) {
        Organization org = orgs.findBySlug(orgSlug).orElseThrow(() -> notFound("org not found: " + orgSlug));
        // MCP tokens are pre-bound to a project; we verify the requested org matches.
        if (ctx.isMcpToken()) {
            if (!org.id().equals(ctx.orgId())) throw forbidden("token not valid for this org");
            return new OrgResolved(org, "member");
        }
        OrgMembership m =
                memberships.find(org.id(), ctx.userId()).orElseThrow(() -> forbidden("not a member of " + orgSlug));
        return new OrgResolved(org, m.role());
    }

    /** A project accepted for deletion is 404 here: one gate rather than each of ~40 controllers checking. */
    public Resolved requireProject(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = requireProjectIncludingDeleting(ctx, orgSlug, projectSlug);
        if (r.project().isDeleting()) {
            throw notFound("project is being deleted: " + projectSlug);
        }
        return r;
    }

    /**
     * Used by the DELETE endpoint, so a retry confirms rather than 404s, and by the project list, so settings can show
     * {@code deleting}.
     */
    public Resolved requireProjectIncludingDeleting(TenantContext ctx, String orgSlug, String projectSlug) {
        OrgResolved o = requireOrg(ctx, orgSlug);
        Project p = projects.findByOrgAndSlug(o.org().id(), projectSlug)
                .orElseThrow(() -> notFound("project not found: " + projectSlug));
        if (ctx.isMcpToken() && !p.id().equals(ctx.projectId())) {
            throw forbidden("token not valid for this project");
        }
        return new Resolved(o.org(), p, o.role());
    }

    public record Resolved(Organization org, Project project, String role) {
        public boolean isOwner() {
            return "owner".equals(role);
        }

        public Role roleEnum() {
            return Role.fromWireOrMember(role);
        }

        public boolean can(Permission permission) {
            return RolePermissions.allows(roleEnum(), permission);
        }

        public void require(Permission permission, String action) {
            requirePermission(roleEnum(), permission, action);
        }
    }

    /** Separate from {@link Resolved} so the project-scoped contract keeps {@code project} non-null. */
    public record OrgResolved(Organization org, String role) {
        public boolean isOwner() {
            return "owner".equals(role);
        }

        public Role roleEnum() {
            return Role.fromWireOrMember(role);
        }

        public void require(Permission permission, String action) {
            requirePermission(roleEnum(), permission, action);
        }
    }

    /** The one RBAC gate; the policy lives only in {@link RolePermissions}. */
    public static void requirePermission(Role role, Permission permission, String action) {
        if (!RolePermissions.allows(role, permission)) {
            throw forbidden("insufficient role (" + role.wire() + ") to " + action);
        }
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}

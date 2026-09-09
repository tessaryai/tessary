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

/**
 * Resolves {orgSlug, projectSlug} from URL path variables into IDs while
 * enforcing the requester's org membership. Centralises the 'this user can
 * see this project' check so every controller doesn't re-implement it.
 *
 * <p>Three resolution shapes:</p>
 * <ul>
 *   <li>{@link #requireOrg(TenantContext, String)} — user must belong to org</li>
 *   <li>{@link #requireProject(TenantContext, String, String)} — same + project exists</li>
 *   <li>{@code requireProjectAccess(TenantContext, String)} — for MCP tokens, where
 *       the project is already bound by the token; just validate the URL agrees</li>
 * </ul>
 */
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

    /** Resolves an org slug to a {@link Resolved} record carrying org + role. 403 on no membership. */
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

    /**
     * The project-scoped resolution every controller uses. A project accepted for deletion resolves as
     * NOT FOUND here — one gate, rather than each of the ~40 project-scoped controllers remembering to
     * check. The row still exists, and will until the purge worker reaches it, but the only honest answer
     * to "read/write this project" once its data is being deleted underneath the caller is 404.
     */
    public Resolved requireProject(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = requireProjectIncludingDeleting(ctx, orgSlug, projectSlug);
        if (r.project().isDeleting()) {
            throw notFound("project is being deleted: " + projectSlug);
        }
        return r;
    }

    /**
     * Resolution that sees a project on its way out.
     *
     * <p>Two callers, both of which need the row precisely because it is being deleted: the DELETE
     * endpoint, so a retry confirms rather than 404s, and the org's project list, so settings can render
     * the {@code deleting} state instead of the row silently vanishing while its data drains.
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

    /** For controllers that only know the projectId (e.g. resolved by id elsewhere). */
    public void requireMembershipForProject(TenantContext ctx, String projectId) {
        Project p = projects.findById(projectId).orElseThrow(() -> notFound("project not found: " + projectId));
        // Same gate as the slug path — a project being purged is not readable by id either.
        if (p.isDeleting()) throw notFound("project is being deleted: " + projectId);
        if (ctx.isMcpToken()) {
            if (!p.id().equals(ctx.projectId())) throw forbidden("token not valid for this project");
            return;
        }
        if (memberships.find(p.orgId(), ctx.userId()).isEmpty()) {
            throw forbidden("not a member of the org owning this project");
        }
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

        /** Throw 403 unless the resolved role holds {@code permission}. {@code action} fills the message. */
        public void require(Permission permission, String action) {
            requirePermission(roleEnum(), permission, action);
        }
    }

    /**
     * Org-only resolution result (no project in scope). Kept separate from
     * {@link Resolved} so the project-scoped contract can keep {@code project}
     * non-null — callers that only resolved an org never have a project to read.
     */
    public record OrgResolved(Organization org, String role) {
        public boolean isOwner() {
            return "owner".equals(role);
        }

        public Role roleEnum() {
            return Role.fromWireOrMember(role);
        }

        public boolean can(Permission permission) {
            return RolePermissions.allows(roleEnum(), permission);
        }

        /** Throw 403 unless the resolved role holds {@code permission}. {@code action} fills the message. */
        public void require(Permission permission, String action) {
            requirePermission(roleEnum(), permission, action);
        }
    }

    /**
     * Central RBAC gate: throws 403 unless {@code role} holds {@code permission}. Every
     * sensitive controller path funnels through here (via {@code Resolved#require} /
     * {@code OrgResolved#require}) so the enforced policy lives in exactly one place —
     * {@link RolePermissions} — and a viewer/billing role can never slip a mutation through.
     */
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

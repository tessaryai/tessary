// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import ai.tessary.auth.AuthProvider;
import ai.tessary.auth.SignupPolicyService;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OrganizationController {

    private static final Logger log = LoggerFactory.getLogger(OrganizationController.class);

    private final TenantService tenants;
    private final OrganizationRepository orgs;
    private final OrgMembershipRepository memberships;
    private final ProjectRepository projects;
    private final PrincipalRepository users;
    private final InvitationRepository invitations;
    private final TenantPathResolver resolver;
    private final AuthProvider provider;
    private final OrgCreationLimit creationLimit;
    private final SignupPolicyService signupPolicy;

    public OrganizationController(
            TenantService tenants,
            OrganizationRepository orgs,
            OrgMembershipRepository memberships,
            ProjectRepository projects,
            PrincipalRepository users,
            InvitationRepository invitations,
            TenantPathResolver resolver,
            AuthProvider provider,
            OrgCreationLimit creationLimit,
            SignupPolicyService signupPolicy) {
        this.tenants = tenants;
        this.orgs = orgs;
        this.memberships = memberships;
        this.projects = projects;
        this.users = users;
        this.invitations = invitations;
        this.resolver = resolver;
        this.provider = provider;
        this.creationLimit = creationLimit;
        this.signupPolicy = signupPolicy;
    }

    public record CreateOrgRequest(@NotBlank String name) {}

    public record CreateProjectRequest(@NotBlank String name, String description) {}

    public record UpdateOrgRequest(@NotBlank String name, String settings) {}

    /** {@code PUT /api/orgs/{slug}/signup-policy} body. */
    public record SignupPolicyRequest(String mode, List<String> domains) {}

    /**
     * The {@code GET /api/orgs/{slug}/signup-policy} view. The policy is instance-wide and
     * lives on the install's first organization; {@code governing} says whether the addressed
     * organization is that one, and {@code governing_org_slug} names it either way.
     */
    public record SignupPolicyView(
            String mode,
            List<String> domains,
            boolean governing,
            @JsonProperty("governing_org_slug") @Nullable String governingOrgSlug) {
        static SignupPolicyView of(SignupPolicy p, boolean governing, @Nullable String governingOrgSlug) {
            return new SignupPolicyView(p.mode().wire(), p.domains(), governing, governingOrgSlug);
        }
    }

    public record UpdateProjectRequest(@NotBlank String name, String description, String settings) {}

    public record AddMemberRequest(
            @NotBlank @Email String email,

            @Pattern(regexp = "owner|admin|member|viewer|billing")
            String role) {}

    public record UpdateMemberRequest(
            @NotBlank @Pattern(regexp = "owner|admin|member|viewer|billing")
            String role) {}

    public record MemberView(
            @JsonProperty("user_id") String userId,
            String email,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("avatar_url") String avatarUrl,
            String role,
            @JsonProperty("created_at") String createdAt) {}

    public record InviteView(
            String id,
            String email,
            String role,
            String state,
            @JsonProperty("created_at") String createdAt) {
        static InviteView of(OrgInvitation inv) {
            return new InviteView(inv.id(), inv.email(), inv.role(), inv.state(), inv.createdAt());
        }
    }

    public record AddMemberResult(String status, MemberView member, InviteView invitation) {
        static AddMemberResult added(MemberView m) {
            return new AddMemberResult("added", m, null);
        }

        static AddMemberResult invited(InviteView i) {
            return new AddMemberResult("invited", null, i);
        }
    }

    @PostMapping("/api/orgs")
    public ApiResponse<Organization> createOrg(TenantContext ctx, @Valid @RequestBody CreateOrgRequest req) {
        String slug = tenants.uniqueSlug(Ids.slugify(req.name()));
        Organization o = new Organization(
                Ids.ulid(), null, slug, req.name(), Instant.now().toString(), null, null);
        // Insert org + owner membership + the guaranteed default project atomically, so a partial
        // failure can't leave an org with no membership or no default project. The owned-org cap
        // is checked in there too, under a per-owner lock, so a concurrent second request from
        // the same user cannot slip past it.
        return ApiResponse.ok(tenants.bootstrapOrg(o, ctx.userId(), creationLimit.maxOwnedOrgsPerUser()));
    }

    @PatchMapping("/api/orgs/{orgSlug}")
    public ApiResponse<Organization> updateOrg(
            TenantContext ctx, @PathVariable String orgSlug, @Valid @RequestBody UpdateOrgRequest req) {
        var r = requireOwner(ctx, orgSlug, "rename the organization");
        // The sign-up policy changes only through its own validated, audited route; a settings blob
        // that carries a different one is refused, and one that omits it keeps the stored policy.
        String settings = req.settings();
        if (settings != null && !settings.isBlank()) {
            boolean carries = !SignupPolicy.omitsPolicy(settings);
            if (carries && SignupPolicy.changesPolicy(r.org().settings(), settings)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "the sign-up policy is validated and audited through PUT /api/orgs/{orgSlug}/signup-policy; "
                                + "it cannot be written through the raw settings blob");
            }
            if (!carries) {
                settings = SignupPolicy.carryInto(r.org().settings(), settings);
            }
        }
        orgs.update(r.org().id(), req.name(), settings);
        return ApiResponse.ok(orgs.findById(r.org().id())
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "organization vanished mid-update")));
    }

    private TenantPathResolver.OrgResolved requireOwner(TenantContext ctx, String orgSlug, String action) {
        var r = resolver.requireOrg(ctx, orgSlug);
        // Irreversible organization lifecycle (rename/archive/delete/transfer) is owner-only,
        // expressed as the ORG_ADMIN permission which only the owner role holds.
        r.require(Permission.ORG_ADMIN, action);
        return r;
    }

    @GetMapping("/api/orgs/{orgSlug}")
    public ApiResponse<Organization> getOrg(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.ORG_VIEW, "view this organization");
        return ApiResponse.ok(r.org());
    }

    @GetMapping("/api/orgs/{orgSlug}/members")
    public ApiResponse<List<MemberView>> members(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.ORG_VIEW, "view members");
        List<MemberView> view = memberships.findByOrgWithUsers(r.org().id()).stream()
                .map(m -> new MemberView(
                        m.principalId(), m.email(), m.displayName(), m.avatarUrl(), m.role(), m.createdAt()))
                .toList();
        return ApiResponse.ok(view);
    }

    @PostMapping("/api/orgs/{orgSlug}/members")
    public ApiResponse<AddMemberResult> addMember(
            TenantContext ctx, @PathVariable String orgSlug, @Valid @RequestBody AddMemberRequest req) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.MEMBERS_MANAGE, "add members");
        String email = req.email().toLowerCase(Locale.ROOT).trim();
        String role = (req.role() == null || req.role().isBlank()) ? OrgMembership.MEMBER : req.role();
        String now = Instant.now().toString();

        Optional<Principal> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            Principal u = existing.get();
            if (memberships.find(r.org().id(), u.id()).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "already a member");
            }
            memberships.insert(OrgMembership.of(r.org().id(), u.id(), role, now));
            return ApiResponse.ok(AddMemberResult.added(
                    new MemberView(u.id(), u.email(), u.displayName(), u.avatarUrl(), role, now)));
        }

        // Unknown email → pending invite, consumed on the invitee's first login.
        String workosInvitationId = null;
        if (provider.isEnabled()) {
            workosInvitationId = provider.createInvitation(email).id();
        }
        OrgInvitation inv = new OrgInvitation(
                Ids.ulid(),
                r.org().id(),
                email,
                role,
                ctx.userId(),
                workosInvitationId,
                OrgInvitation.PENDING,
                now,
                null,
                null);
        invitations.upsertPending(inv);
        return ApiResponse.ok(AddMemberResult.invited(InviteView.of(inv)));
    }

    @GetMapping("/api/orgs/{orgSlug}/signup-policy")
    public ApiResponse<SignupPolicyView> signupPolicy(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.MEMBERS_MANAGE, "read the sign-up policy");
        return ApiResponse.ok(SignupPolicyView.of(
                signupPolicy.current(),
                signupPolicy.governs(r.org().id()),
                signupPolicy.governingOrg().map(Organization::slug).orElse(null)));
    }

    /**
     * Who may create an account on this install. Owner and admin, the same people who manage the
     * invitations that are the escape hatch in every mode; the change is written to the audit log.
     */
    @PutMapping("/api/orgs/{orgSlug}/signup-policy")
    public ApiResponse<SignupPolicyView> updateSignupPolicy(
            TenantContext ctx, @PathVariable String orgSlug, @RequestBody SignupPolicyRequest req) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.MEMBERS_MANAGE, "change the sign-up policy");
        String governingSlug =
                signupPolicy.governingOrg().map(Organization::slug).orElse(null);
        if (!signupPolicy.governs(r.org().id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "the sign-up policy is instance-wide and is managed under organization " + governingSlug + ", not "
                            + orgSlug);
        }
        SignupPolicy next;
        try {
            next = SignupPolicy.of(req.mode(), req.domains());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return ApiResponse.ok(
                SignupPolicyView.of(signupPolicy.update(r.org(), next, ctx.userId()), true, governingSlug));
    }

    @GetMapping("/api/orgs/{orgSlug}/invitations")
    public ApiResponse<List<InviteView>> invitations(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.MEMBERS_MANAGE, "view invitations");
        List<InviteView> view = invitations.findPendingByOrg(r.org().id()).stream()
                .map(InviteView::of)
                .toList();
        return ApiResponse.ok(view);
    }

    @DeleteMapping("/api/orgs/{orgSlug}/invitations/{invitationId}")
    public ApiResponse<Void> revokeInvitation(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String invitationId) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.MEMBERS_MANAGE, "revoke invitations");
        OrgInvitation inv = invitations
                .findById(invitationId)
                .filter(i -> i.orgId().equals(r.org().id()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such invitation"));
        invitations.markRevoked(inv.id(), Instant.now().toString());
        if (inv.workosInvitationId() != null && provider.isEnabled()) {
            try {
                provider.revokeInvitation(inv.workosInvitationId());
            } catch (RuntimeException e) {
                log.warn("workos invitation revoke failed invitationId={}", inv.id());
            }
        }
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/orgs/{orgSlug}/members/{userId}")
    public ApiResponse<MemberView> updateMember(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String userId,
            @Valid @RequestBody UpdateMemberRequest req) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.MEMBERS_MANAGE, "change roles");
        OrgMembership target = memberships
                .find(r.org().id(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not a member"));
        if (target.role().equals(req.role())) {
            // No-op: just return the current view rather than re-touching the row.
            return ApiResponse.ok(memberViewFor(r.org().id(), userId));
        }
        // Granting or revoking the owner role is owner-only — a non-owner admin (who holds
        // MEMBERS_MANAGE) must not be able to mint or strip owners and escalate past their own role.
        boolean touchesOwner = OrgMembership.OWNER.equals(target.role()) || OrgMembership.OWNER.equals(req.role());
        if (touchesOwner && !r.isOwner()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an owner can grant or revoke the owner role");
        }
        // Demoting the last owner would orphan the org.
        if (OrgMembership.OWNER.equals(target.role())
                && !OrgMembership.OWNER.equals(req.role())
                && memberships.countOwners(r.org().id()) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "cannot demote the last owner — promote another member first");
        }
        memberships.updateRole(r.org().id(), userId, req.role());
        return ApiResponse.ok(memberViewFor(r.org().id(), userId));
    }

    @DeleteMapping("/api/orgs/{orgSlug}/members/{userId}")
    public ApiResponse<Void> removeMember(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String userId) {
        var r = resolver.requireOrg(ctx, orgSlug);
        boolean isSelf = userId.equals(ctx.userId());
        // Two paths in: a member-manager removing anyone, or a user removing themselves.
        if (!isSelf) {
            r.require(Permission.MEMBERS_MANAGE, "remove other members");
        }
        OrgMembership target = memberships
                .find(r.org().id(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not a member"));
        // Removing an owner is owner-only — a non-owner admin must not be able to strip owners.
        if (OrgMembership.OWNER.equals(target.role()) && !isSelf && !r.isOwner()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an owner can remove another owner");
        }
        // Don't allow removing the last owner — applies equally to self-leave.
        if (OrgMembership.OWNER.equals(target.role())
                && memberships.countOwners(r.org().id()) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    isSelf
                            ? "you are the last owner — promote another member before leaving"
                            : "cannot remove the last owner — promote another member first");
        }
        memberships.delete(r.org().id(), userId);
        return ApiResponse.ok(null);
    }

    private MemberView memberViewFor(String orgId, String userId) {
        return memberships.findByOrgWithUsers(orgId).stream()
                .filter(m -> m.principalId().equals(userId))
                .findFirst()
                .map(m -> new MemberView(
                        m.principalId(), m.email(), m.displayName(), m.avatarUrl(), m.role(), m.createdAt()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "member vanished mid-update"));
    }

    @GetMapping("/api/orgs/{orgSlug}/projects")
    public ApiResponse<List<Project>> listProjects(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.ORG_VIEW, "view projects");
        return ApiResponse.ok(projects.findByOrg(r.org().id()));
    }

    @PostMapping("/api/orgs/{orgSlug}/projects")
    public ApiResponse<Project> createProject(
            TenantContext ctx, @PathVariable String orgSlug, @Valid @RequestBody CreateProjectRequest req) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.ORG_MANAGE, "create projects");
        Project p = tenants.createProject(r.org().id(), req.name(), req.description());
        return ApiResponse.ok(p);
    }

    /**
     * The connect gate's quiet escape hatch: lazily create (or return the existing) sample
     * project for this org. Gated on {@code ORG_MANAGE}, the same permission {@link #createProject}
     * requires two methods above — this is a genuine mutation (a project row, plus every table
     * {@code SampleProjectSeedListener} seeds after commit), and {@code Role.VIEWER}'s contract is
     * read-only, not "read-only except for demo data". Idempotent regardless: a repeat call from any
     * caller who already holds {@code ORG_MANAGE} returns the existing sample project rather than
     * creating a second one.
     */
    @PostMapping("/api/orgs/{orgSlug}/sample-project")
    public ApiResponse<Project> ensureSampleProject(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.ORG_MANAGE, "start with a sample project");
        return ApiResponse.ok(tenants.ensureSampleProject(r.org().id()));
    }

    @GetMapping("/api/orgs/{orgSlug}/projects/{projectSlug}")
    public ApiResponse<Project> getProject(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view this project");
        return ApiResponse.ok(r.project());
    }

    @PatchMapping("/api/orgs/{orgSlug}/projects/{projectSlug}")
    public ApiResponse<Project> updateProject(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody UpdateProjectRequest req) {
        var r = requireProjectManager(ctx, orgSlug, projectSlug, "rename a project");
        projects.update(r.project().id(), req.name(), req.description(), req.settings());
        return ApiResponse.ok(projects.findById(r.project().id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project vanished mid-update")));
    }

    @PostMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/default")
    public ApiResponse<Project> makeProjectDefault(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = requireProjectManager(ctx, orgSlug, projectSlug, "set the default project");
        if (r.project().isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot make an archived project the default");
        }
        tenants.setDefaultProject(r.org().id(), r.project().id());
        return ApiResponse.ok(projects.findById(r.project().id()).orElseThrow());
    }

    @PostMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/archive")
    public ApiResponse<Project> archiveProject(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = requireProjectManager(ctx, orgSlug, projectSlug, "archive a project");
        if (r.project().isDefault()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "cannot archive the default project — promote another project first");
        }
        projects.setArchived(r.project().id(), Instant.now().toString());
        log.info("project archived projectId={}", r.project().id());
        return ApiResponse.ok(projects.findById(r.project().id()).orElseThrow());
    }

    @PostMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/unarchive")
    public ApiResponse<Project> unarchiveProject(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = requireProjectManager(ctx, orgSlug, projectSlug, "unarchive a project");
        projects.setArchived(r.project().id(), null);
        return ApiResponse.ok(projects.findById(r.project().id()).orElseThrow());
    }

    /**
     * Accept a project deletion. Three indexed writes and it returns — the data goes in the background.
     *
     * <p>This used to run {@code DELETE FROM project} inline and wait for the whole cascade, which on a
     * project with real traffic meant tens of minutes holding a pooled connection, a request the browser
     * gave up on, and a single-statement cascade that then rolled back whole so the retry started from
     * nothing. What is synchronous now is only what has to be: the marker that stops every other route
     * from touching this project, and the key revocation that stops anything from writing to it.
     */
    @DeleteMapping("/api/orgs/{orgSlug}/projects/{projectSlug}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> deleteProject(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = requireProjectManagerIncludingDeleting(ctx, orgSlug, projectSlug, "delete a project");
        // An organization must always retain a default project; deleting it (or the last
        // project) would break that invariant. Force a promote/transfer first.
        if (r.project().isDefault()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "cannot delete the default project — promote another project first");
        }
        String now = Instant.now().toString();
        // Marking, revoking and enqueueing happen atomically in one transaction — see
        // TenantService.deleteProjectAsync's javadoc for why that matters.
        var accepted = tenants.deleteProjectAsync(r.project().id(), now);
        if (!accepted.accepted()) {
            // Already accepted — a double click or a retry of a request that did land. Idempotent, and
            // deliberately not a 409: the caller asked for this project to be gone and it is going.
            return ApiResponse.ok(null);
        }
        log.info(
                "project delete queued projectId={} keysRevoked={}", r.project().id(), accepted.keysRevoked());
        return ApiResponse.ok(null);
    }

    private TenantPathResolver.Resolved requireProjectManager(
            TenantContext ctx, String orgSlug, String projectSlug, String action) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, action);
        return r;
    }

    /**
     * The delete endpoint's resolver. Identical but for seeing through the deleting gate, because that
     * gate would otherwise make a retried DELETE 404 instead of confirming what is already happening.
     */
    private TenantPathResolver.Resolved requireProjectManagerIncludingDeleting(
            TenantContext ctx, String orgSlug, String projectSlug, String action) {
        var r = resolver.requireProjectIncludingDeleting(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, action);
        return r;
    }
}

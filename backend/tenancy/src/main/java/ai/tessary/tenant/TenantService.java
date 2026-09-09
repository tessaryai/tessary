// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * High-level tenancy operations: ensure-user-on-login, ensure-org, create-project,
 * resolve-by-slug, plus the organization/project lifecycle (rename, archive, default-project
 * guarantee, transfer-ownership, delete). Stays thin: controllers compose at the boundary,
 * this class owns the "what does it mean to be a tenant" semantics.
 */
@Service
public class TenantService {

    private final PrincipalRepository users;
    private final OrganizationRepository orgs;
    private final OrgMembershipRepository memberships;
    private final ProjectRepository projects;
    private final InvitationRepository invitations;
    private final ApplicationEventPublisher events;
    private final ApiKeyRepository apiKeys;
    private final VerifiedTokenCache tokenCache;
    private final ProjectDeleteJobRepository deleteJobs;

    /**
     * Self-reference through the Spring proxy. Two callers need it, for the same reason. {@link
     * #ensureDefaultProject} is intentionally non-transactional so it can catch a concurrent
     * first-login's unique-index violation, and the 3-arg {@link #createProject(String, String,
     * String)} only picks the {@code makeDefault} flag before handing off, but the mint they
     * delegate to must still run in its own transaction. A plain {@code this.createProject(...)}
     * would be a self-invocation that bypasses the proxy and therefore the {@code @Transactional}
     * boundary, leaving the mint's inserts to autocommit one at a time, so both route through
     * {@code self} instead.
     */
    private final TenantService self;

    public TenantService(
            PrincipalRepository users,
            OrganizationRepository orgs,
            OrgMembershipRepository memberships,
            ProjectRepository projects,
            InvitationRepository invitations,
            ApplicationEventPublisher events,
            ApiKeyRepository apiKeys,
            VerifiedTokenCache tokenCache,
            ProjectDeleteJobRepository deleteJobs,
            @Lazy TenantService self) {
        this.events = events;
        this.users = users;
        this.orgs = orgs;
        this.memberships = memberships;
        this.projects = projects;
        this.invitations = invitations;
        this.apiKeys = apiKeys;
        this.tokenCache = tokenCache;
        this.deleteJobs = deleteJobs;
        this.self = self;
    }

    /** Insert-or-update on WorkOS user identity; returns the canonical principal row. */
    public Principal upsertUserFromWorkos(String workosUserId, String email, String displayName, String avatarUrl) {
        Optional<Principal> existing = users.findByWorkosId(workosUserId);
        String now = Instant.now().toString();
        if (existing.isPresent()) {
            users.updateProfile(existing.get().id(), displayName, avatarUrl, now);
            return Principal.human(
                    existing.get().id(),
                    workosUserId,
                    email,
                    displayName,
                    avatarUrl,
                    existing.get().createdAt(),
                    now);
        }
        // No row for this WorkOS id, but the email may already be on file: the same
        // person after a WorkOS environment switch, which mints a fresh user id for
        // an unchanged email. Re-bind the existing row to the new id; a plain insert
        // would violate UNIQUE(email) and lock the user out.
        Optional<Principal> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            users.rebindWorkosId(byEmail.get().id(), workosUserId, displayName, avatarUrl, now);
            return Principal.human(
                    byEmail.get().id(),
                    workosUserId,
                    email,
                    displayName,
                    avatarUrl,
                    byEmail.get().createdAt(),
                    now);
        }
        Principal fresh = Principal.human(Ids.ulid(), workosUserId, email, displayName, avatarUrl, now, now);
        users.insert(fresh);
        return fresh;
    }

    /**
     * Ensure the user has at least one org membership, and that the resolved organization has a
     * default project. If WorkOS gave us an {@code organization_id}, mirror it locally (and
     * treat as a 'member' join). If not, mint a personal org named after the user's email
     * handle and add them as 'owner'. Idempotent.
     */
    public Organization ensureDefaultOrg(Principal user, String workosOrgId) {
        Organization org = resolveOrCreateOrg(user, workosOrgId);
        ensureDefaultProject(org.id());
        return org;
    }

    private Organization resolveOrCreateOrg(Principal user, String workosOrgId) {
        if (workosOrgId != null && !workosOrgId.isBlank()) {
            Optional<Organization> existing = orgs.findByWorkosOrgId(workosOrgId);
            if (existing.isPresent()) {
                ensureMembership(existing.get().id(), user.id(), OrgMembership.MEMBER);
                return existing.get();
            }
            // WorkOS org we haven't mirrored yet: create with WorkOS id as the link
            String slug = uniqueSlug(workosOrgId.toLowerCase(Locale.ROOT).replace("org_", "org-"));
            Organization fresh = newOrg(Ids.ulid(), workosOrgId, slug, slug);
            orgs.insert(fresh);
            ensureMembership(fresh.id(), user.id(), OrgMembership.MEMBER);
            return fresh;
        }
        // No WorkOS org → user belongs only to a personal org. Reuse if it exists.
        List<Organization> userOrgs = orgs.findByUserId(user.id());
        if (!userOrgs.isEmpty()) return userOrgs.get(0);

        String personalName = personalOrgNameFor(user);
        String slug = uniqueSlug(Ids.slugify(personalName));
        Organization personal = newOrg(Ids.ulid(), null, slug, personalName);
        orgs.insert(personal);
        ensureMembership(personal.id(), user.id(), OrgMembership.OWNER);
        return personal;
    }

    private static Organization newOrg(String id, String workosOrgId, String slug, String name) {
        return new Organization(id, workosOrgId, slug, name, Instant.now().toString(), null, null);
    }

    /**
     * Atomically bootstraps a freshly-named organization: insert the org, add the creator as
     * owner, and mint the guaranteed default project, all in one transaction, so a partial
     * failure can never leave an org with no membership or no default project.
     *
     * <p>The owned-org cap is enforced inside the transaction: {@link
     * OrganizationRepository#lockOrgCreationFor} serializes creates per owner first, so the
     * count that follows is exact even under concurrent requests from the same user.
     *
     * <p>The default-project mint here is contention-free, since the org row isn't visible to
     * other sessions until commit; the login-path {@link #ensureDefaultProject} has no such
     * guarantee and must tolerate the race.
     */
    @Transactional
    public Organization bootstrapOrg(Organization org, String ownerUserId, int maxOwnedOrgs) {
        orgs.lockOrgCreationFor(ownerUserId);
        if (orgs.countOwnedBy(ownerUserId) >= maxOwnedOrgs) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "org creation limit reached (" + maxOwnedOrgs + ")");
        }
        orgs.insert(org);
        ensureMembership(org.id(), ownerUserId, OrgMembership.OWNER);
        createProject(org.id(), "Default", null, true);
        return org;
    }

    /**
     * Guarantee the organization has exactly one default project. Promotes the existing default
     * if present; otherwise promotes the earliest-created project, or mints a starter project
     * when the organization has none. Idempotent: safe to call on every login.
     *
     * <p>Not {@code @Transactional} itself: the starter-project mint runs in its own transaction
     * (via {@link #createProject}), so when two concurrent first-logins race to create the
     * default, the loser's unique-index violation ({@code ux_project_one_default_per_org}) stays
     * confined to that inner transaction. We catch it here and re-resolve to the winner's project
     * rather than letting the login 500.
     */
    public Project ensureDefaultProject(String orgId) {
        Optional<Project> current = projects.findDefaultForOrg(orgId);
        if (current.isPresent()) return current.get();

        try {
            // The sample project (Project#isSample) is filtered out here: it's a lazily-created,
            // deletable demo row, never the org's routing target, and an org that somehow reaches
            // this branch with only a sample project on the books must mint a genuine default
            // rather than promote a project full of generated data.
            List<Project> existing = projects.findByOrg(orgId).stream()
                    .filter(p -> !p.isSample())
                    .toList();
            if (!existing.isEmpty()) {
                return self.promoteToDefault(existing.get(0));
            }
            return self.createProject(orgId, "Default", null, true);
        } catch (DuplicateKeyException raced) {
            // Concurrent first-login already created/promoted the default: re-resolve to it.
            return projects.findDefaultForOrg(orgId).orElseThrow(() -> raced);
        }
    }

    /** Promote an already-resolved project to its organization's default in its own transaction. */
    @Transactional
    public Project promoteToDefault(Project promote) {
        projects.setDefault(promote.id(), true);
        return new Project(
                promote.id(),
                promote.orgId(),
                promote.slug(),
                promote.name(),
                promote.description(),
                promote.createdAt(),
                promote.archivedAt(),
                promote.settings(),
                true,
                promote.deletingAt());
    }

    /**
     * Turn any pending invitations addressed to this user's email into real org
     * memberships, then mark them accepted. Called on first login. Idempotent:
     * membership insert is ON CONFLICT DO NOTHING, and an accepted invite won't
     * be re-found.
     */
    public void consumePendingInvitations(Principal user) {
        String raw = user.email();
        if (raw == null) return;
        String now = Instant.now().toString();
        // Invitations are stored lower-cased (OrganizationController#addMember); the principal's
        // email is whatever the provider gave, so match case the same way the sign-up policy does.
        String email = raw.trim().toLowerCase(Locale.ROOT);
        for (OrgInvitation inv : invitations.findPendingByEmail(email)) {
            ensureMembership(inv.orgId(), user.id(), inv.role());
            invitations.markAccepted(inv.id(), now);
        }
    }

    /**
     * Transfers ownership of an organization to one of its members, atomically: promotes the
     * recipient to owner (bounded by their owned-org cap) and steps the transferrer down to
     * member, in one transaction. {@link OrganizationRepository#lockOrgCreationFor} keys on the
     * recipient, whose count is being bounded, so the cap check is exact even under concurrent
     * transfers, and the same lock serializes against concurrent creates by that user.
     *
     * <p>404 if the recipient is not a member, 429 at the cap, no-op if already owner, no
     * demotion when transferring to oneself. The "never orphan an organization" invariant
     * (&gt;= 1 owner) holds at every step.
     */
    @Transactional
    public void transferOwnership(String orgId, String fromUserId, String toUserId, int maxOwnedOrgs) {
        orgs.lockOrgCreationFor(toUserId);
        OrgMembership recipient = memberships
                .find(orgId, toUserId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "recipient is not a member of this organization"));
        if (orgs.countOwnedBy(toUserId) >= maxOwnedOrgs) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "recipient already owns the maximum number of organizations");
        }
        if (!OrgMembership.OWNER.equals(recipient.role())) {
            memberships.updateRole(orgId, toUserId, OrgMembership.OWNER);
        }
        if (!toUserId.equals(fromUserId)) {
            memberships.updateRole(orgId, fromUserId, OrgMembership.MEMBER);
        }
    }

    private void ensureMembership(String orgId, String userId, String role) {
        memberships.insert(OrgMembership.of(orgId, userId, role, Instant.now().toString()));
    }

    private String personalOrgNameFor(Principal user) {
        String email = user.email();
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@')) + "'s org";
        }
        return "Personal";
    }

    /** Append -2, -3 ... if the slug already exists. */
    public String uniqueSlug(String base) {
        if (orgs.findBySlug(base).isEmpty()) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (orgs.findBySlug(candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("could not find unique slug for " + base);
    }

    /** Same idea, scoped to one org for project slugs. */
    public String uniqueProjectSlug(String orgId, String base) {
        if (projects.findByOrgAndSlug(orgId, base).isEmpty()) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (projects.findByOrgAndSlug(orgId, candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("could not find unique project slug for " + base);
    }

    public Project createProject(String orgId, String name, String description) {
        // The first project in an organization is its default; subsequent ones are not. Delegates
        // rather than duplicating the insert, so there is exactly one path that can mint a project.
        //
        // Through `self`, not `this`: this is the overload the HTTP create goes through, and a plain
        // self-invocation would bypass the proxy and therefore run the mint with no transaction open
        // at all.
        return self.createProject(orgId, name, description, projects.countByOrg(orgId) == 0);
    }

    /** Mint a project with a slug unique in its org. */
    @Transactional
    public Project createProject(String orgId, String name, String description, boolean makeDefault) {
        String slug = uniqueProjectSlug(orgId, Ids.slugify(name));
        if (makeDefault) {
            projects.clearDefaultForOrg(orgId);
        }
        Project p = new Project(
                Ids.ulid(), orgId, slug, name, description, Instant.now().toString(), null, null, makeDefault, null);
        projects.insert(p);
        // Provisioning that can safely lag the create (the built-in classifier catalog, the default
        // case alert) hangs off this instead of tenant/ reaching into those slices. Delivered
        // AFTER_COMMIT, so a listener never sees a project that rolled back and never blocks the
        // create.
        events.publishEvent(new ProjectCreatedEvent(p.id()));
        return p;
    }

    /**
     * Runs {@code action} once this transaction has finished, whichever way it finished, or
     * immediately when there is no transaction to wait for.
     *
     * <p>Uses {@code afterCompletion}, not {@code afterCommit}: Spring skips {@code afterCommit}
     * when {@code doCommit} throws but the database had in fact committed. For a cache eviction
     * that is the worst case to skip, since the revocation is durable but the cache still answers
     * for the key, and it does not self-heal, because a retry finds {@code markDeleting} already
     * false and returns {@code ALREADY_ACCEPTED} without reaching this line again. {@code
     * afterCompletion} always runs; on a genuine rollback it evicts entries that did not need
     * evicting, which costs one bcrypt on the next request and nothing else.
     */
    private static void afterCompletion(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.run();
            }
        });
    }

    /**
     * Lazily creates the org's "sample project": a real, deletable project row marked via {@code
     * settings: {"sample": true}} ({@link Project#isSample()}), never wired through {@link
     * #ensureDefaultOrg} or the signup tail. It exists only once a user follows the connect
     * gate's "Start with a sample project" link.
     *
     * <p>Idempotent: a second call finds the existing sample project rather than minting a
     * duplicate. Delegates the mint to {@link #createProject(String, String, String, boolean)}
     * with {@code makeDefault=false}, since a sample project must never become the org's
     * routing target, then stamps the marker with a follow-up {@code UPDATE}.
     */
    @Transactional
    public Project ensureSampleProject(String orgId) {
        for (Project existing : projects.findByOrg(orgId)) {
            if (existing.isSample()) return existing;
        }
        Project created = self.createProject(
                orgId,
                "Sample project",
                "Generated data for exploring Tessary before you connect your own traces.",
                false);
        projects.update(created.id(), null, null, SAMPLE_PROJECT_SETTINGS);
        return new Project(
                created.id(),
                created.orgId(),
                created.slug(),
                created.name(),
                created.description(),
                created.createdAt(),
                created.archivedAt(),
                SAMPLE_PROJECT_SETTINGS,
                created.isDefault(),
                created.deletingAt());
    }

    private static final String SAMPLE_PROJECT_SETTINGS = "{\"sample\":true}";

    /**
     * Promote one project to be its organization's default, demoting the previous default. Callers
     * gate on the project being unarchived (you can't route new work to an archived project).
     */
    @Transactional
    public void setDefaultProject(String orgId, String projectId) {
        projects.clearDefaultForOrg(orgId);
        projects.setDefault(projectId, true);
    }

    /** The result of {@link #deleteProjectAsync}: whether this call is the one that accepted the delete
     *  (vs. a retry of one already in flight), and how many API keys it revoked. */
    public record ProjectDeleteAcceptance(boolean accepted, int keysRevoked) {
        static final ProjectDeleteAcceptance ALREADY_ACCEPTED = new ProjectDeleteAcceptance(false, 0);
    }

    /**
     * Accepts a project deletion: marks it doomed, revokes every API key that could still write
     * to it, and queues the purge, all three in one transaction, so either all three land or none
     * do and the next retry starts clean. Without the transaction, a process that died between
     * the mark and the revoke could leave a project locked out of every project-scoped route
     * (the mark alone is enough for {@link ai.tessary.auth.TenantPathResolver} to refuse it)
     * while its API keys stayed live.
     */
    @Transactional
    public ProjectDeleteAcceptance deleteProjectAsync(String projectId, String now) {
        if (!projects.markDeleting(projectId, now)) {
            // Already accepted: a double click or a retry of a request that did land.
            return ProjectDeleteAcceptance.ALREADY_ACCEPTED;
        }
        int revoked = apiKeys.revokeAllForProject(projectId, now);
        // AFTER the transaction, not here. The UPDATE above is deliberately synchronous so nothing
        // new lands in a project on its way out, but evicting the token cache inside this
        // transaction reopens the same window: a verification on another connection could read
        // the bumped generation, then read the row before this transaction commits, see it still
        // live, and cache it with a generation nothing will invalidate again. READ COMMITTED
        // makes that reachable, and ingest authenticates on the key alone, so the stale entry
        // would be a writable deleted project for a full TTL. ApiKeyService.revoke is safe from
        // this only because it isn't transactional: its UPDATE autocommits before it evicts.
        afterCompletion(() -> tokenCache.invalidateProject(projectId));
        deleteJobs.enqueue(projectId, now);
        return new ProjectDeleteAcceptance(true, revoked);
    }
}

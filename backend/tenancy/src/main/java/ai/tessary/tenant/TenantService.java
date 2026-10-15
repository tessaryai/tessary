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
     * Self-reference through the Spring proxy: {@link #ensureDefaultProject} is deliberately non-transactional, but the
     * mint it delegates to must run in its own transaction, and a plain {@code this.createProject(...)} would bypass
     * the {@code @Transactional} boundary.
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
        // The same person after a WorkOS environment switch gets a fresh user id for an unchanged email. Re-bind the
        // existing row; a plain insert would violate UNIQUE(email) and lock the user out.
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

    /** Idempotent. */
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
            String slug = uniqueSlug(workosOrgId.toLowerCase(Locale.ROOT).replace("org_", "org-"));
            Organization fresh = newOrg(Ids.ulid(), workosOrgId, slug, slug);
            orgs.insert(fresh);
            events.publishEvent(new OrganizationCreatedEvent(fresh.id()));
            ensureMembership(fresh.id(), user.id(), OrgMembership.MEMBER);
            return fresh;
        }
        List<Organization> userOrgs = orgs.findByUserId(user.id());
        if (!userOrgs.isEmpty()) return userOrgs.get(0);

        String personalName = personalOrgNameFor(user);
        String slug = uniqueSlug(Ids.slugify(personalName));
        Organization personal = newOrg(Ids.ulid(), null, slug, personalName);
        orgs.insert(personal);
        events.publishEvent(new OrganizationCreatedEvent(personal.id()));
        ensureMembership(personal.id(), user.id(), OrgMembership.OWNER);
        return personal;
    }

    private static Organization newOrg(String id, String workosOrgId, String slug, String name) {
        return new Organization(id, workosOrgId, slug, name, Instant.now().toString(), null, null);
    }

    /**
     * One transaction, so a partial failure never leaves an org with no membership or default project.
     * {@link OrganizationRepository#lockOrgCreationFor} serializes creates per owner, so the owned-org count is exact
     * under concurrency.
     */
    @Transactional
    public Organization bootstrapOrg(Organization org, String ownerUserId, int maxOwnedOrgs) {
        orgs.lockOrgCreationFor(ownerUserId);
        if (orgs.countOwnedBy(ownerUserId) >= maxOwnedOrgs) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "org creation limit reached (" + maxOwnedOrgs + ")");
        }
        orgs.insert(org);
        events.publishEvent(new OrganizationCreatedEvent(org.id()));
        ensureMembership(org.id(), ownerUserId, OrgMembership.OWNER);
        createProject(org.id(), "Default", null, true);
        return org;
    }

    /**
     * Not {@code @Transactional}: the starter-project mint runs in its own transaction, so a concurrent first-login's
     * {@code ux_project_one_default_per_org} violation stays confined there and is caught to re-resolve to the winner
     * rather than 500 the login.
     */
    public Project ensureDefaultProject(String orgId) {
        Optional<Project> current = projects.findDefaultForOrg(orgId);
        if (current.isPresent()) return current.get();

        try {
            // The sample project is a deletable demo row, never the routing target, so an org with only a sample must
            // mint a genuine default.
            List<Project> existing = projects.findByOrg(orgId).stream()
                    .filter(p -> !p.isSample())
                    .toList();
            if (!existing.isEmpty()) {
                return self.promoteToDefault(existing.get(0));
            }
            return self.createProject(orgId, "Default", null, true);
        } catch (DuplicateKeyException raced) {
            return projects.findDefaultForOrg(orgId).orElseThrow(() -> raced);
        }
    }

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

    /** Idempotent: membership insert is ON CONFLICT DO NOTHING, and an accepted invite won't be re-found. */
    public void consumePendingInvitations(Principal user) {
        String raw = user.email();
        if (raw == null) return;
        String now = Instant.now().toString();
        // Invitations are stored lower-cased; match the provider's email the same way the sign-up policy does.
        String email = raw.trim().toLowerCase(Locale.ROOT);
        for (OrgInvitation inv : invitations.findPendingByEmail(email)) {
            ensureMembership(inv.orgId(), user.id(), inv.role());
            invitations.markAccepted(inv.id(), now);
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

    public String uniqueSlug(String base) {
        if (orgs.findBySlug(base).isEmpty()) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (orgs.findBySlug(candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("could not find unique slug for " + base);
    }

    public String uniqueProjectSlug(String orgId, String base) {
        if (projects.findByOrgAndSlug(orgId, base).isEmpty()) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (projects.findByOrgAndSlug(orgId, candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("could not find unique project slug for " + base);
    }

    public Project createProject(String orgId, String name, String description) {
        // Through `self`, not `this`: this is the HTTP create path, and a self-invocation would run the mint with no
        // transaction open.
        return self.createProject(orgId, name, description, projects.countByOrg(orgId) == 0);
    }

    @Transactional
    public Project createProject(String orgId, String name, String description, boolean makeDefault) {
        String slug = uniqueProjectSlug(orgId, Ids.slugify(name));
        if (makeDefault) {
            projects.clearDefaultForOrg(orgId);
        }
        Project p = new Project(
                Ids.ulid(), orgId, slug, name, description, Instant.now().toString(), null, null, makeDefault, null);
        projects.insert(p);
        // Delivered AFTER_COMMIT, so a listener never sees a project that rolled back and never blocks the create.
        events.publishEvent(new ProjectCreatedEvent(p.id()));
        return p;
    }

    /**
     * {@code afterCompletion}, not {@code afterCommit}: Spring skips afterCommit when {@code doCommit} throws after the
     * database had committed, leaving a revoked key cached with no self-heal. On a genuine rollback the extra eviction
     * costs one bcrypt.
     */
    private static void afterCompletion(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.run();
            }
        });
    }

    /**
     * Created only when a user follows the "Start with a sample project" link. Minted with {@code makeDefault=false},
     * since a sample must never become the routing target.
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

    /** Callers gate on the project being unarchived. */
    @Transactional
    public void setDefaultProject(String orgId, String projectId) {
        projects.clearDefaultForOrg(orgId);
        projects.setDefault(projectId, true);
    }

    public record ProjectDeleteAcceptance(boolean accepted, int keysRevoked) {
        static final ProjectDeleteAcceptance ALREADY_ACCEPTED = new ProjectDeleteAcceptance(false, 0);
    }

    /**
     * Mark, revoke and queue land in one transaction: otherwise a crash after the mark could leave the project refused
     * by {@link ai.tessary.auth.TenantPathResolver} while its API keys stayed live.
     */
    @Transactional
    public ProjectDeleteAcceptance deleteProjectAsync(String projectId, String now) {
        if (!projects.markDeleting(projectId, now)) {
            return ProjectDeleteAcceptance.ALREADY_ACCEPTED;
        }
        int revoked = apiKeys.revokeAllForProject(projectId, now);
        // AFTER the transaction: evicting inside it lets another connection read the bumped generation, then the
        // still-live row, and cache it unrevokably under READ COMMITTED. Ingest authenticates on the key alone, so that
        // would be a writable deleted project for a full TTL.
        afterCompletion(() -> tokenCache.invalidateProject(projectId));
        deleteJobs.enqueue(projectId, now);
        return new ProjectDeleteAcceptance(true, revoked);
    }
}

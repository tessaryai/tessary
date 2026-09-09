// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link TenantService} owns the user/org/project bootstrap path. Three
 * behaviours need to be locked:
 *
 * <ol>
 *   <li>WorkOS callback is idempotent — sign-in on attempt 2 doesn't create
 *       a duplicate user or membership.</li>
 *   <li>Personal-org branch creates an owner; WorkOS-org branch creates a
 *       member. Future code may rely on the distinction.</li>
 *   <li>Slug uniqueness is enforced at the {@code UNIQUE} index level; the
 *       service must round-trip until a free slot exists.</li>
 * </ol>
 */
@SpringBootTest
class TenantServiceTest {

    /** These tests are about bootstrap atomicity, not the owned-org cap. */
    private static final int UNCAPPED = Integer.MAX_VALUE;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    TenantService tenants;

    @Autowired
    PrincipalRepository users;

    @Autowired
    OrganizationRepository orgs;

    @Autowired
    OrgMembershipRepository memberships;

    @Autowired
    ProjectRepository projects;

    @Autowired
    JdbcClient jdbc;

    @Test
    void upsertUser_isIdempotentByWorkosId() {
        Principal first = tenants.upsertUserFromWorkos("user_idem_001", "alice@example.com", "Alice", null);
        Principal second = tenants.upsertUserFromWorkos(
                "user_idem_001", "alice@example.com", "Alice the Second", "https://avatar/2.png");
        assertEquals(first.id(), second.id(), "same WorkOS id → same app_user.id");
        assertEquals("Alice the Second", second.displayName());
        assertEquals("https://avatar/2.png", second.avatarUrl());
        assertNotEquals(first.lastSeenAt(), second.lastSeenAt(), "lastSeenAt must tick on re-login");
    }

    @Test
    void ensureDefaultOrg_noWorkosOrg_createsPersonalAsOwner() {
        Principal u = tenants.upsertUserFromWorkos("user_personal_001", "solo@example.com", "Solo", null);

        Organization first = tenants.ensureDefaultOrg(u, null);
        assertNull(first.workosOrgId(), "personal org has no WorkOS link");
        assertEquals(
                "owner", memberships.find(first.id(), u.id()).orElseThrow().role(), "self-created org → owner role");

        // Second call must NOT create a second personal org.
        Organization second = tenants.ensureDefaultOrg(u, null);
        assertEquals(first.id(), second.id());
        assertEquals(1, orgs.findByUserId(u.id()).size());
    }

    @Test
    void ensureDefaultOrg_withWorkosOrg_mirrorsAsMember() {
        Principal u = tenants.upsertUserFromWorkos("user_wo_001", "wo@example.com", "Workos User", null);

        Organization mirrored = tenants.ensureDefaultOrg(u, "org_workos_42");
        assertEquals("org_workos_42", mirrored.workosOrgId());
        assertEquals(
                "member",
                memberships.find(mirrored.id(), u.id()).orElseThrow().role(),
                "org joined via WorkOS → member role (owner is reserved for self-created)");

        // Idempotent: same WorkOS org id resolves to the same row.
        Organization second = tenants.ensureDefaultOrg(u, "org_workos_42");
        assertEquals(mirrored.id(), second.id());
    }

    @Test
    void uniqueSlug_appendsSuffixOnCollision() {
        // Manually plant a couple of orgs with the same slug-base.
        String base = tenants.uniqueSlug("clash"); // first taker gets "clash"
        Organization a = new Organization(
                Ids.ulid(), null, base, "Clash A", java.time.Instant.now().toString(), null, null);
        orgs.insert(a);

        String next = tenants.uniqueSlug("clash");
        assertEquals("clash-2", next);

        Organization b = new Organization(
                Ids.ulid(), null, next, "Clash B", java.time.Instant.now().toString(), null, null);
        orgs.insert(b);

        assertEquals("clash-3", tenants.uniqueSlug("clash"));
    }

    @Test
    void createProject_collisionWithinSameOrg_appendsSuffix() {
        Principal u = tenants.upsertUserFromWorkos("user_proj_001", "p@example.com", "P", null);
        Organization org = tenants.ensureDefaultOrg(u, null);

        Project first = tenants.createProject(org.id(), "Agent", null);
        Project second = tenants.createProject(org.id(), "Agent", null);
        assertNotEquals(first.slug(), second.slug());
        assertEquals("agent", first.slug());
        assertEquals("agent-2", second.slug());
    }

    @Test
    void createProject_sameSlugInDifferentOrgs_allowed() {
        Principal u1 = tenants.upsertUserFromWorkos("user_diff_001", "a-diff@example.com", "A", null);
        Organization o1 = tenants.ensureDefaultOrg(u1, null);

        Principal u2 = tenants.upsertUserFromWorkos("user_diff_002", "b-diff@example.com", "B", null);
        Organization o2 = tenants.ensureDefaultOrg(u2, null);

        Project a = tenants.createProject(o1.id(), "Agent", null);
        Project b = tenants.createProject(o2.id(), "Agent", null);
        assertEquals("agent", a.slug());
        assertEquals("agent", b.slug());
        assertNotNull(projects.findByOrgAndSlug(o1.id(), "agent").orElseThrow());
        assertNotNull(projects.findByOrgAndSlug(o2.id(), "agent").orElseThrow());
    }

    @Test
    void ensureDefaultOrg_guaranteesExactlyOneDefaultProject() {
        Principal u = tenants.upsertUserFromWorkos("user_defproj_001", "defproj@example.com", "DefProj", null);
        Organization org = tenants.ensureDefaultOrg(u, null);

        // An organization must always have exactly one default project, minted if none exists.
        Project def = projects.findDefaultForOrg(org.id()).orElseThrow();
        assertTrue(def.isDefault());

        // Idempotent: re-resolving doesn't mint a second default or a duplicate project.
        tenants.ensureDefaultOrg(u, null);
        assertEquals(1, projects.findByOrg(org.id()).size());
        assertEquals(
                def.id(), projects.findDefaultForOrg(org.id()).orElseThrow().id());
    }

    @Test
    void createProject_firstIsDefault_subsequentAreNot() {
        // Mint a bare org without a default project so the first createProject decides the default.
        Organization org = new Organization(
                Ids.ulid(),
                null,
                tenants.uniqueSlug("bare"),
                "Bare",
                java.time.Instant.now().toString(),
                null,
                null);
        orgs.insert(org);

        Project a = tenants.createProject(org.id(), "Alpha", null);
        Project b = tenants.createProject(org.id(), "Beta", null);
        assertTrue(a.isDefault(), "first project in an organization is its default");
        assertTrue(!b.isDefault(), "later projects are not default");
        assertEquals(a.id(), projects.findDefaultForOrg(org.id()).orElseThrow().id());
    }

    @Test
    void setDefaultProject_movesTheDefaultAndKeepsExactlyOne() {
        Principal u = tenants.upsertUserFromWorkos("user_setdef_001", "setdef@example.com", "SetDef", null);
        Organization org = tenants.ensureDefaultOrg(u, null);
        Project first = projects.findDefaultForOrg(org.id()).orElseThrow();
        Project second = tenants.createProject(org.id(), "Second", null);

        tenants.setDefaultProject(org.id(), second.id());

        assertEquals(
                second.id(), projects.findDefaultForOrg(org.id()).orElseThrow().id());
        assertTrue(!projects.findById(first.id()).orElseThrow().isDefault(), "old default is demoted");
    }

    @Test
    void projectUpdate_isPartial_nameOnlyRenameDoesNotWipeSettings() {
        Principal u = tenants.upsertUserFromWorkos("user_partial_001", "partial@example.com", "Partial", null);
        Organization org = tenants.ensureDefaultOrg(u, null);
        Project p = projects.findDefaultForOrg(org.id()).orElseThrow();

        // Seed a settings blob + description, then do a name-only PATCH (the rest null).
        projects.update(p.id(), null, "a description", "{\"k\":1}");
        projects.update(p.id(), "Renamed", null, null);

        Project after = projects.findById(p.id()).orElseThrow();
        assertEquals("Renamed", after.name(), "name updated");
        assertEquals("a description", after.description(), "omitted description preserved");
        assertEquals("{\"k\":1}", after.settings(), "omitted settings blob preserved (not nulled)");
    }

    @Test
    void orgUpdate_isPartial_nameOnlyRenameDoesNotWipeSettings() {
        Principal u = tenants.upsertUserFromWorkos("user_orgpartial_001", "orgpartial@example.com", "OrgPartial", null);
        Organization org = tenants.ensureDefaultOrg(u, null);

        orgs.update(org.id(), null, "{\"theme\":\"dark\"}");
        orgs.update(org.id(), "Renamed Organization", null);

        Organization after = orgs.findById(org.id()).orElseThrow();
        assertEquals("Renamed Organization", after.name(), "name updated");
        assertEquals("{\"theme\":\"dark\"}", after.settings(), "omitted settings blob preserved (not nulled)");
    }

    @Test
    void bootstrapOrg_createsOrgOwnerAndDefaultProject() {
        Principal u = tenants.upsertUserFromWorkos("user_bootstrap_001", "bootstrap@example.com", "Bootstrap", null);
        Organization o = new Organization(
                Ids.ulid(),
                null,
                tenants.uniqueSlug("bootstrap-ws"),
                "Bootstrap WS",
                java.time.Instant.now().toString(),
                null,
                null);

        Organization created = tenants.bootstrapOrg(o, u.id(), UNCAPPED);

        assertEquals(
                "owner", memberships.find(created.id(), u.id()).orElseThrow().role(), "creator is owner");
        Project def = projects.findDefaultForOrg(created.id()).orElseThrow();
        assertTrue(def.isDefault(), "bootstrap mints exactly one default project");
        assertEquals(1, projects.findByOrg(created.id()).size());
    }

    @Test
    void upsertUser_secondUserDifferentWorkosId_isIsolated() {
        Principal a = tenants.upsertUserFromWorkos("user_iso_001", "a@example.com", "A", null);
        Principal b = tenants.upsertUserFromWorkos("user_iso_002", "b@example.com", "B", null);
        assertNotEquals(a.id(), b.id());
        assertTrue(users.findById(a.id()).isPresent());
        assertTrue(users.findById(b.id()).isPresent());
    }

    /**
     * {@link TenantService#ensureSampleProject} must not disturb the invariant {@link
     * #ensureDefaultOrg_guaranteesExactlyOneDefaultProject} pins — additive, exactly one default
     * project, exactly one sample project, and the sample project is never that default.
     */
    @Test
    void ensureSampleProject_doesNotDisturbDefaultProjectInvariant() {
        Principal u = tenants.upsertUserFromWorkos("user_sample_001", "sample@example.com", "Sample", null);
        Organization org = tenants.ensureDefaultOrg(u, null);

        Project sample = tenants.ensureSampleProject(org.id());

        List<Project> all = projects.findByOrg(org.id());
        assertEquals(2, all.size(), "the org now has its default project plus the sample project");

        long defaults = all.stream().filter(Project::isDefault).count();
        assertEquals(1, defaults, "still exactly one default project");
        assertFalse(sample.isDefault(), "the sample project is never the org's default");
        assertTrue(sample.isSample(), "the returned project reports itself as a sample");

        long samples = all.stream().filter(Project::isSample).count();
        assertEquals(1, samples, "exactly one project on the books is a sample project");

        // The demo data SampleProjectSeedListener (analysis module) writes on the same
        // ProjectCreatedEvent -- AFTER_COMMIT listeners run synchronously before this call returns,
        // so it is already there. Belt-and-braces per that class's own doc (ConnectGate's isSample()
        // short-circuit does not depend on this), but worth pinning since it silently swallows its
        // own failures and a broken insert would otherwise go unnoticed until someone opens the demo
        // project by hand.
        Long findings = jdbc.sql("SELECT COUNT(*) FROM finding WHERE project_id = :pid")
                .param("pid", sample.id())
                .query(Long.class)
                .single();
        Long cases = jdbc.sql("SELECT COUNT(*) FROM eval_case WHERE project_id = :pid")
                .param("pid", sample.id())
                .query(Long.class)
                .single();
        Long rcaReports = jdbc.sql("SELECT COUNT(*) FROM rca_report WHERE project_id = :pid")
                .param("pid", sample.id())
                .query(Long.class)
                .single();
        // The standalone fabricated finding/case/report were folded into the showcase rows so that
        // every seeded row hangs off a real classifier. These are what SampleProjectSeedListener now writes:
        //   findings — four metric-drift (drift 0-3) plus two tool-error (drift 4-5);
        //   cases     — three, numbered 1..3, which is what the seeder's own comment says it takes so
        //               that the first case a real detector opens on the project continues from 4;
        //   reports   — one, seeded only by seedCaseA, the single case carrying a full agentic RCA.
        assertEquals(6, findings, "the sample project's demo + showcase findings were seeded");
        assertEquals(3, cases, "the sample project's demo + showcase cases were seeded");
        assertEquals(1, rcaReports, "the sample project's demo + showcase RCA report was seeded");
    }

    /** A second call (a double click, a stale tab) finds the existing sample project rather than
     *  minting a duplicate. */
    @Test
    void ensureSampleProject_isIdempotent() {
        Principal u = tenants.upsertUserFromWorkos("user_sample_002", "sample2@example.com", "Sample2", null);
        Organization org = tenants.ensureDefaultOrg(u, null);

        Project first = tenants.ensureSampleProject(org.id());
        Project second = tenants.ensureSampleProject(org.id());

        assertEquals(first.id(), second.id(), "the same sample project is returned, not a duplicate");
        assertEquals(
                2,
                projects.findByOrg(org.id()).size(),
                "still exactly two projects -- default plus the one sample project");
    }
}

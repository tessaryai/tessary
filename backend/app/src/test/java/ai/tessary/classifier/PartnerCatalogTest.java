// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.ClassifierCatalogWorker;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The partner-facing classifier catalog, against real {@code org_feature_flag} rows.
 *
 * <p>The claim under test is that two orgs' classifier lists differ by <b>configuration only</b>:
 * every module stays defined for everyone, and which of them an org sees is one row.
 *
 * <ul>
 *   <li>The non-launch classifiers stay defined and are hidden by flag; an org targeted on sees
 *       all of them, an org that has withheld them sees the launch catalog, with no code branch
 *       between the two.
 *   <li>Turning a capability off reaches <b>already-seeded</b> projects, not just newly created
 *       ones: the classifier leaves the list, 404s by id, and stops being swept.
 *   <li>Switched off is not withdrawn. Withholding writes nothing, so the project's own
 *       {@code enabled} switch survives the flag going off <em>and</em> a full resync (which
 *       runs the retirement path), and flipping the flag back on restores exactly what the
 *       project had.
 * </ul>
 *
 * <p>Most cases seed while their capabilities are ON and narrow afterwards, so each starts from a
 * fully-seeded project, the only starting state in which "takes effect on existing projects"
 * means anything. A capability going <em>off</em> takes effect on the next read, because
 * withholding is a filter; one going <em>on</em> takes effect on the next re-seed, because the
 * row has to be inserted before anything can list it. In production {@link
 * ClassifierCatalogWorker} is that re-seed; here it is usually an explicit {@code seedBuiltIns},
 * and in {@link #flagOnReachesAProjectWithNoTracesAtAll()} it is the worker itself, because
 * <em>which projects that worker scans</em> is the thing under test.
 */
@SpringBootTest
class PartnerCatalogTest {

    @Autowired
    TenantService tenants;

    @Autowired
    OrgFeatureFlagRepository overrides;

    @Autowired
    DbFeatureFlags flags;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierRepository rows;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    ClassifierCatalogWorker catalogWorker;

    @Autowired
    SubstrateReadRepository substrate;

    /** Pin one capability ON for one org. */
    private void grant(String orgId, Capability capability) {
        overrides.upsert(orgId, capability.wire(), true);
        flags.invalidate(orgId);
    }

    /**
     * Pin one capability OFF for one org: an explicit row, not the absence of one. The absence of
     * a row means ON for everything under test here, so withholding has to be stated.
     */
    private void withhold(String orgId, Capability capability) {
        overrides.upsert(orgId, capability.wire(), false);
        flags.invalidate(orgId);
    }

    /** The classifier keys the project's org may see, which is what the list endpoint serves. */
    private Set<String> visibleKeys(String projectId) {
        return classifiers.list(projectId).stream()
                .map(ClassifierRow::classifierKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the row still physically exists for the project, ignoring the flag layer entirely. */
    private ClassifierRow storedRow(String projectId, String key) {
        return ClassifierRows.byKey(rows, projectId, key).orElseThrow();
    }

    @Test
    void flaggingOffReachesAnAlreadySeededProjectAndStopsItSweeping() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-withdraw");
        String projectId = fix.project().id();
        // Seeded and visible while the capability is on: the state a project is in before anyone
        // flips anything.
        grant(fix.org().id(), Capability.SECRET_LEAK);
        classifiers.seedBuiltIns(projectId);
        assertTrue(visibleKeys(projectId).contains("secret_leak"), "on, the classifier is in the list");
        String id = storedRow(projectId, "secret_leak").id();
        assertEquals(id, classifiers.get(projectId, id).id(), "and readable by id");

        // Turn it off for this org. No re-seed, no project edit, no deploy.
        withhold(fix.org().id(), Capability.SECRET_LEAK);

        assertFalse(visibleKeys(projectId).contains("secret_leak"), "it leaves an EXISTING project's list");
        // 404 rather than 403: from this org's point of view there is no such classifier, and a "you can't
        // have this" would itself be a mention of a capability the org doesn't have.
        assertThrows(TessaryException.class, () -> classifiers.get(projectId, id), "and 404s by id");
        assertThrows(
                TessaryException.class, () -> classifiers.setEnabled(projectId, id, false), "and cannot be written");

        classifiers.enqueueEnabled(projectId);
        assertFalse(
                jobs.listByProject(projectId).stream().anyMatch(j -> id.equals(j.classifierId())),
                "and stops being swept, which is where turning it off actually stops costing anything");
    }

    /**
     * A flag going ON must reach a project that has <b>never sent a single trace</b>: catalog
     * provisioning must not be a function of trace ingestion, or a capability flip on a quiet
     * project would silently wait for its first production span before taking effect.
     *
     * <p>The test drives {@link ClassifierCatalogWorker#tick()} rather than {@code
     * resyncBuiltIns}, because which projects get scanned is what is under test. Asserting
     * mid-test that the project is absent from {@code projectsWithObservations()} pins that a
     * project with zero spans is reached anyway.
     */
    @Test
    void flagOnReachesAProjectWithNoTracesAtAll() {
        // secret_leak is withheld before the project exists, which is what keeps this premise
        // reachable: the creation-time ClassifierSeedListener cannot seed it, so the only thing that
        // can make the row appear later is the periodic reconcile.
        var fix = TenantFixture.bootstrap(
                tenants, "catalog-no-traces", org -> withhold(org.id(), Capability.SECRET_LEAK));
        String projectId = fix.project().id();
        assertFalse(ClassifierRows.byKey(rows, projectId, "secret_leak").isPresent(), "off at creation, never seeded");

        grant(fix.org().id(), Capability.SECRET_LEAK); // the org gains the capability, nothing else changes
        assertFalse(
                substrate.projectsWithObservations().contains(projectId),
                "the project has never ingested a span, which used to make it invisible to provisioning");

        catalogWorker.tick();

        assertTrue(
                visibleKeys(projectId).contains("secret_leak"),
                "the reconcile scans the PROJECT table, so a quiet project gets the classifier its org now "
                        + "has — no trace required, and on the reconcile's own cadence rather than whenever "
                        + "the project's first span happens to arrive");
        assertTrue(storedRow(projectId, "secret_leak").enabled(), "seeded ON, like every built-in");
    }

    /**
     * Guards against a future edit that expresses "flagged off" by disabling the row instead of
     * withholding it: it asserts the row is still {@code enabled} after the flag went off and
     * after a resync has run the retirement path over it, which is exactly what a withdrawal
     * would have destroyed.
     */
    @Test
    void flagOffNeverWritesToTheRow() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-nowrite");
        String projectId = fix.project().id();
        grant(fix.org().id(), Capability.SECRET_LEAK);
        classifiers.seedBuiltIns(projectId);

        String id = storedRow(projectId, "secret_leak").id();
        assertTrue(storedRow(projectId, "secret_leak").enabled(), "it seeds enabled");

        withhold(fix.org().id(), Capability.SECRET_LEAK);
        classifiers.resyncBuiltIns(fix.project()); // seeds, then runs retireDroppedBuiltIns over every row

        ClassifierRow stored = storedRow(projectId, "secret_leak");
        assertEquals(id, stored.id(), "the row is neither deleted nor re-inserted under a new id");
        assertTrue(stored.builtIn(), "and is still a built-in");
        assertTrue(
                stored.enabled(),
                "and is still ENABLED — turning a capability off suppresses, it does not withdraw. Retirement "
                        + "is the only path allowed to disable a row, and it keys on catalog membership alone.");
    }
}

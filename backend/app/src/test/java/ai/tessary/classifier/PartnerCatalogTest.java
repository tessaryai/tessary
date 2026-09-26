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
 * The partner-facing classifier catalog against real {@code org_feature_flag} rows: two orgs' lists differ by
 * configuration only.
 *
 * <p>Non-launch classifiers stay defined and are hidden by flag. Turning a capability off reaches already-seeded
 * projects: the classifier leaves the list, 404s by id, and stops being swept. Off is not withdrawn: the project's
 * {@code enabled} switch survives the flag going off and a resync, and flipping it back restores it.
 *
 * <p>Off takes effect on the next read (a filter); on takes effect on the next re-seed, done here by {@code
 * seedBuiltIns} or by {@link ClassifierCatalogWorker} itself.
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

    /** An explicit OFF row: no row means ON here. */
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

    /** Whether the row physically exists, ignoring the flag layer. */
    private ClassifierRow storedRow(String projectId, String key) {
        return ClassifierRows.byKey(rows, projectId, key).orElseThrow();
    }

    @Test
    void flaggingOffReachesAnAlreadySeededProjectAndStopsItSweeping() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-withdraw");
        String projectId = fix.project().id();
        // Seeded and visible while the capability is on.
        grant(fix.org().id(), Capability.SECRET_LEAK);
        classifiers.seedBuiltIns(projectId);
        assertTrue(visibleKeys(projectId).contains("secret_leak"), "on, the classifier is in the list");
        String id = storedRow(projectId, "secret_leak").id();
        assertEquals(id, classifiers.get(projectId, id).id(), "and readable by id");

        // Off for this org. No re-seed, no project edit, no deploy.
        withhold(fix.org().id(), Capability.SECRET_LEAK);

        assertFalse(visibleKeys(projectId).contains("secret_leak"), "it leaves an EXISTING project's list");
        // 404, not 403: a "you can't have this" would itself mention a capability the org doesn't have.
        assertThrows(TessaryException.class, () -> classifiers.get(projectId, id), "and 404s by id");
        assertThrows(
                TessaryException.class, () -> classifiers.setEnabled(projectId, id, false), "and cannot be written");

        classifiers.enqueueEnabled(projectId);
        assertFalse(
                jobs.listByProject(projectId).stream().anyMatch(j -> id.equals(j.classifierId())),
                "and stops being swept, which is where turning it off actually stops costing anything");
    }

    /**
     * A flag going on reaches a project that never sent a trace, or a quiet project would wait for its first span.
     * Drives {@link ClassifierCatalogWorker#tick()} because which projects get scanned is under test.
     */
    @Test
    void flagOnReachesAProjectWithNoTracesAtAll() {
        // Withheld before the project exists, so the creation-time seed skips it and only the reconcile can add it.
        var fix = TenantFixture.bootstrap(
                tenants, "catalog-no-traces", org -> withhold(org.id(), Capability.SECRET_LEAK));
        String projectId = fix.project().id();
        assertFalse(ClassifierRows.byKey(rows, projectId, "secret_leak").isPresent(), "off at creation, never seeded");

        grant(fix.org().id(), Capability.SECRET_LEAK); // the only change
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
     * Guards against expressing "flagged off" by disabling the row: it stays {@code enabled} after the flag goes off
     * and a resync runs retirement.
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
        classifiers.resyncBuiltIns(fix.project()); // seeds, then retires dropped built-ins

        ClassifierRow stored = storedRow(projectId, "secret_leak");
        assertEquals(id, stored.id(), "the row is neither deleted nor re-inserted under a new id");
        assertTrue(stored.builtIn(), "and is still a built-in");
        assertTrue(
                stored.enabled(),
                "and is still ENABLED — turning a capability off suppresses, it does not withdraw. Retirement "
                        + "is the only path allowed to disable a row, and it keys on catalog membership alone.");
    }
}

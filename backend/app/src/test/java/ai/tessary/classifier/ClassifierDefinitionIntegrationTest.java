// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Acceptance for the signal <b>definition</b> model + lifecycle: the built-in catalog seeds
 * per project (idempotently), and a definition can be enabled/disabled through its lifecycle. Run
 * against the real pgvector Postgres (Testcontainers), so the signal schema is applied for real.
 */
@SpringBootTest
@Import(TurnGrainTestDetectionConfig.class)
class ClassifierDefinitionIntegrationTest {

    @Autowired
    ClassifierService service;

    @Autowired
    BuiltInClassifierCatalog catalog;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void seedsBuiltInsOnProjectCreationIdempotently() {
        // No generation run, no repo, no graders: just a project. Auto-classification reads traces,
        // so creating the project is the whole precondition (ClassifierSeedListener).
        String pid = bootstrapGranted("signal-seed").project().id();

        var defs = service.list(pid);
        assertEquals(
                9,
                defs.size(),
                "all built-ins seed on project creation (incl. all five fitting-tier ones: behaviour "
                        + "drift, duration drift, cost drift, tool errors and SOP conformance)");

        assertEquals(0, service.seedBuiltIns(pid), "re-seeding is a no-op (idempotent)");
        assertEquals(9, service.list(pid).size(), "all built-ins are listable");
        assertTrue(defs.stream().allMatch(ClassifierRow::builtIn), "all seeded signals are marked built_in");
        // Every built-in seeds enabled, with no per-classifier exceptions: whether a classifier
        // actually runs for an org is a capability-flag decision, not something the seeded row
        // encodes. What reaches a project at all is asserted in PartnerCatalogTest.
        assertTrue(defs.stream().allMatch(ClassifierRow::enabled), "every built-in seeds enabled");
    }

    @Test
    void secretLeakIsWiredToTheCuratedPatternDetector() {
        String pid = bootstrapGranted("signal-secret-leak").project().id();

        ClassifierRow secretLeak = signals.findByKey(pid, "secret_leak").orElseThrow();
        assertEquals(
                BuiltInDetector.Kind.SECRET_LEAK,
                secretLeak.detector(),
                "Secret Leak is wired to the curated credential-pattern detector");
        assertEquals(2, secretLeak.version(), "the catalog version bumped to 2 so re-seeding re-syncs it");
        assertTrue(secretLeak.enabled(), "Secret Leak seeds enabled");
    }

    @Test
    void resyncDisablesBuiltInsThatLeftTheCatalog() {
        String pid = bootstrapGranted("signal-retire").project().id();

        // Mimic a project seeded by the OLD catalog: a 'wins' built-in row that is no longer shipped.
        String now = Instant.now().toString();
        signals.insert(new ClassifierRow(
                Ids.ulid(),
                pid,
                "wins",
                "Wins",
                "A clear success moment — praise or an explicit goal completion (retired built-in).",
                "wins",
                null,
                true, // built_in, exactly as the old catalog seeded it
                1,
                true, // enabled: the zombie state this test retires
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        service.resyncBuiltIns(pid);

        ClassifierRow wins = signals.findByKey(pid, "wins").orElseThrow();
        assertFalse(wins.enabled(), "a built-in whose key left the catalog is disabled on resync");
        assertTrue(wins.builtIn(), "the retired row keeps its built_in marker");
        assertTrue(
                service.list(pid).stream().anyMatch(s -> "wins".equals(s.classifierKey())),
                "the retired built-in stays listed — history is kept, never deleted");
        assertFalse(
                signals.listEnabled(pid).stream().anyMatch(s -> "wins".equals(s.classifierKey())),
                "the retired built-in drops out of the enabled set the worker enqueues");

        service.resyncBuiltIns(pid);
        assertFalse(
                signals.findByKey(pid, "wins").orElseThrow().enabled(), "retirement is idempotent across heartbeats");
    }

    @Test
    void resyncInsertsCatalogAdditionsIntoSeededProjects() {
        String pid = bootstrapGranted("signal-catalog-add").project().id();

        // Mimic a project seeded by an OLD catalog: only 'frustration' exists, at an old version;
        // the catalog has since grown (e.g. the groundedness built-in) and bumped versions. Clear the
        // seed-on-create catalog first so the old-catalog row is the project's entire starting state.
        jdbc.sql("DELETE FROM classifier WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        String now = Instant.now().toString();
        signals.insert(new ClassifierRow(
                Ids.ulid(),
                pid,
                "frustration",
                "Frustration",
                "User frustration in a turn (old-catalog definition).",
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                true, // built_in: this project WAS seeded, by an older catalog
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        service.resyncBuiltIns(pid);

        var defs = service.list(pid);
        assertEquals(9, defs.size(), "resync inserts the catalog built-ins the old seed never had");
        assertTrue(
                signals.findByKey(pid, "groundedness").isPresent(),
                "a built-in added to the catalog after seeding reaches the already-seeded project");
        assertTrue(defs.stream().allMatch(ClassifierRow::builtIn), "everything inserted is marked built_in");
        assertEquals(
                catalog.builtIns().stream()
                        .filter(b -> "frustration".equals(b.classifierKey()))
                        .findFirst()
                        .orElseThrow()
                        .version(),
                signals.findByKey(pid, "frustration").orElseThrow().version(),
                "the pre-existing old-version row is re-synced to the current catalog version");
        // The version is only the TRIGGER; config_json is the payload: the operating band and the
        // context policy both live there. The old row above stores a null config, so if resync carried
        // the version across without the config, a seeded project would keep scoring on whatever it was
        // seeded with while REPORTING the current version, a silent no-op that reads as shipped.
        assertEquals(
                catalog.builtIns().stream()
                        .filter(b -> "frustration".equals(b.classifierKey()))
                        .findFirst()
                        .orElseThrow()
                        .defaultConfigJson(),
                signals.findByKey(pid, "frustration").orElseThrow().configJson(),
                "resync carries the catalog's config_json, not just its version, onto the seeded row");

        service.resyncBuiltIns(pid);
        assertEquals(9, service.list(pid).size(), "resync stays idempotent across heartbeats");
    }

    @Test
    void resyncSeedsAProjectThatSomehowHasNone() {
        String pid = bootstrapGranted("signal-unseeded").project().id();
        // Simulate a project that predates seed-on-create: strip the catalog back out, so resync is
        // the only thing that can put it back. Auto-classification reads traces, so a project with
        // traffic must end up classified whether or not anyone ever ran generation.
        // Table is `signal`; the persisted name predates the domain rename (see backend/AGENTS.md).
        jdbc.sql("DELETE FROM classifier WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        assertTrue(service.list(pid).isEmpty(), "precondition: the project starts with no classifiers");

        service.resyncBuiltIns(pid);

        assertEquals(
                catalog.builtIns().size(),
                service.list(pid).size(),
                "the heartbeat self-heals a never-seeded project — no generation run, and so no repo, required");
    }

    @Test
    void enableDisableLifecycle() {
        String pid = bootstrapGranted("signal-lifecycle").project().id();
        ClassifierRow secretLeak = signals.findByKey(pid, "secret_leak").orElseThrow();

        ClassifierRow disabled = service.setEnabled(pid, secretLeak.id(), false);
        assertFalse(disabled.enabled(), "a signal can be disabled through its lifecycle");
        assertFalse(
                signals.listEnabled(pid).stream().anyMatch(s -> s.id().equals(secretLeak.id())),
                "a disabled signal drops out of the enabled set the worker sweeps");

        ClassifierRow reEnabled = service.setEnabled(pid, secretLeak.id(), true);
        assertTrue(reEnabled.enabled(), "and re-enabled");
    }

    /**
     * Bootstrap a tenant whose org has all four capability-gated classifiers switched on before its
     * project is created.
     *
     * <p>Two things make this necessary. {@code behavior_drift}, {@code sop_conformance}, {@code
     * frustration}, and {@code groundedness} default off, so without a grant these cases would
     * assert the capability default rather than the behavior they name. And the grant has to
     * precede the project, because project creation is what seeds the built-in classifiers: grant
     * afterwards and the classifier row is never inserted, leaving the test hunting findings from a
     * classifier the project doesn't have.
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.BEHAVIOR_DRIFT);
            capabilities.grant(org.id(), Capability.SOP_CONFORMANCE);
            capabilities.grant(org.id(), Capability.FRUSTRATION);
            capabilities.grant(org.id(), Capability.GROUNDEDNESS);
        });
    }
}

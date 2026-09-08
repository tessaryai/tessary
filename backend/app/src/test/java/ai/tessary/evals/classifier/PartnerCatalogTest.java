// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.catalog.ClassifierCatalogWorker;
import ai.tessary.evals.classifier.substrate.SubstrateReadRepository;
import ai.tessary.evals.classifier.worker.ClassifierJobRepository;
import ai.tessary.evals.featureflags.DbFeatureFlags;
import ai.tessary.evals.featureflags.OrgFeatureFlagRepository;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The partner-facing catalog (launch segment D), against real {@code org_feature_flag} rows.
 *
 * <p>The claim under test is that two orgs' classifier lists differ by <b>configuration only</b>: every module
 * stays defined for everyone, and which of them an org sees is one row. Concretely:
 *
 * <ol>
 *   <li><b>D1/D3</b> — the non-launch classifiers stay defined and are hidden by flag; one org targeted on
 *       sees all of them while an org that has withheld them sees the launch catalog, with no code branch
 *       between them.
 *   <li><b>D2</b> — turning a capability off reaches <b>already-seeded</b> projects, not just newly created ones:
 *       the classifier leaves the list, 404s by id, and stops being swept.
 *   <li><b>risk 7</b> — switched off is not withdrawn. Withholding writes nothing, so the project's own
 *       {@code enabled} switch survives the flag going off <em>and</em> a full resync (which runs the
 *       retirement path), and flipping the flag back on restores exactly what the project had rather than
 *       the catalog's seed default.
 * </ol>
 *
 * <p><b>THE BASELINE INVERTED WITH THE OPEN EDITION.</b> This used to run behind the {@code enterprise} tier
 * with every classifier flagged off globally and targeted on per org, so "a partner does not see it" was the
 * ambient state. The open edition defaults every capability ON except the four paid classifiers, so withholding
 * is now the thing a case has to say out loud: an org that must not see a classifier gets an explicit
 * {@code false} row. That is a truer test of the same claim — the withholding is now visible in the test rather
 * than inherited from a deployment property.
 *
 * <p><b>Most cases seed while their capabilities are ON and narrow afterwards</b>, so each starts from a fully-seeded
 * project — the only starting state in which "takes effect on existing projects" means anything. The ordering
 * is also the asymmetry itself: a capability going <em>off</em> takes effect on the next read, because
 * withholding is a filter, whereas one going <em>on</em> takes effect on the next re-seed, because the row has
 * to be inserted before anything can list it. In production {@link ClassifierCatalogWorker} is that re-seed; in most
 * cases here it is an explicit {@code seedBuiltIns}, and in {@link #flagOnReachesAProjectWithNoTracesAtAll()}
 * it is the worker itself, because <em>which projects that worker scans</em> is the thing under test.
 */
@SpringBootTest
class PartnerCatalogTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    /**
     * The classifiers a launch partner must not see (decision D3). {@code tool_error} is absent because it is
     * a launch classifier, and the other two launch keys are absent because they are launch classifiers too.
     */
    private static final List<Capability> NON_LAUNCH = List.of(
            Capability.FRUSTRATION,
            Capability.GROUNDEDNESS,
            Capability.SECRET_LEAK,
            Capability.MALFORMED_OUTPUT,
            Capability.BEHAVIOR_DRIFT);

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
     * Pin one capability OFF for one org — an explicit row, not the absence of one. In the open edition the
     * absence of a row means ON for everything under test here, so withholding has to be stated.
     */
    private void withhold(String orgId, Capability capability) {
        overrides.upsert(orgId, capability.wire(), false);
        flags.invalidate(orgId);
    }

    /** Drop the org's opinion entirely, returning the capability to the edition default. */
    private void clear(String orgId, Capability capability) {
        overrides.delete(orgId, capability.wire());
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
        return rows.findByKey(projectId, key).orElseThrow();
    }

    @Test
    void weSeeEveryClassifierAndAPartnerSeesTheLaunchCatalog() {
        var us = TenantFixture.bootstrap(tenants, "catalog-us");
        var partner = TenantFixture.bootstrap(tenants, "catalog-partner");

        // Both orgs run the same code on the same edition. The ONLY difference below is one row per org.
        // Seed BOTH projects with the whole catalog first, so the partner's rows below are ones that already
        // exist — the point of D2 is that a capability change reaches a project running for months.
        for (Capability capability : NON_LAUNCH) {
            grant(us.org().id(), capability);
            grant(partner.org().id(), capability);
        }
        classifiers.seedBuiltIns(us.project().id());
        classifiers.seedBuiltIns(partner.project().id());

        // Now narrow the partner. No re-seed, no project edit, no deploy.
        for (Capability capability : NON_LAUNCH) {
            withhold(partner.org().id(), capability);
        }

        Set<String> ourKeys = visibleKeys(us.project().id());
        Set<String> partnerKeys = visibleKeys(partner.project().id());

        assertTrue(
                ourKeys.containsAll(
                        Set.of("frustration", "groundedness", "secret_leak", "malformed_output", "behavior_drift")),
                "the org that kept them sees the whole catalog: " + ourKeys);
        assertTrue(
                ourKeys.containsAll(Set.of("duration_drift", "cost_drift", "tool_error")),
                "including the launch three");

        assertEquals(
                Set.of("duration_drift", "cost_drift", "tool_error"),
                partnerKeys,
                "a partner's catalog is the launch classifiers and nothing else — the original two, "
                        + "tool_error having joined them with its own detector");

        // D1: hidden, not withdrawn. Every module the partner cannot see is still defined and still seeded
        // as a row in their project — one row away from appearing, with no migration and no re-seed.
        for (String hidden :
                List.of("frustration", "groundedness", "secret_leak", "malformed_output", "behavior_drift")) {
            assertTrue(
                    rows.findByKey(partner.project().id(), hidden).isPresent(),
                    hidden + " is hidden by the override layer, not removed from the partner's project");
        }
    }

    @Test
    void flaggingOffReachesAnAlreadySeededProjectAndStopsItSweeping() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-withdraw");
        String projectId = fix.project().id();
        // Seeded and visible while the capability is on — the state a project is in before anyone flips
        // anything.
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
        assertThrows(EvalsException.class, () -> classifiers.get(projectId, id), "and 404s by id");
        assertThrows(EvalsException.class, () -> classifiers.setEnabled(projectId, id, false), "and cannot be written");

        classifiers.enqueueEnabled(projectId);
        assertFalse(
                jobs.listByProject(projectId).stream().anyMatch(j -> id.equals(j.classifierId())),
                "and stops being swept, which is where turning it off actually stops costing anything");
    }

    /**
     * What a flag going off reaches BEYOND the classifier list, and what it deliberately does not.
     *
     * <p>Hiding the row alone would be a half-measure: its findings sit in the Findings list on the same
     * page, and an alert rule on it can still page someone about a detector their org no longer has. Both
     * are gated. What stays is history — past detections and any case already open — because a case is a
     * record of something that happened, and withdrawing the detector does not un-happen it.
     */
    @Test
    void flagOffAlsoGatesFindingsAndAlertsButNotHistory() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-downstream");
        String projectId = fix.project().id();
        // behavior_drift is a PAID classifier and off by default in an open build, so this grant is what
        // gives the case something to withdraw. An explicit override still wins for an unavailable
        // capability — see CapabilityService.resolveOne — which is what makes this test possible while the
        // drift code is still physically in the open tree (epic 1 issue 4 moves it).
        grant(fix.org().id(), Capability.BEHAVIOR_DRIFT);
        classifiers.seedBuiltIns(projectId);

        // Findings from the classifier are visible while it is on, and gone the moment it is off.
        // The detector kinds a project may NOT see is the seam both the list and the by-id guard read.
        assertFalse(
                classifiers.unavailableDetectorKinds(projectId).contains(BuiltInDetector.Kind.BEHAVIOR_DRIFT),
                "on, behaviour drift's findings are the org's to read");

        withhold(fix.org().id(), Capability.BEHAVIOR_DRIFT);

        assertTrue(
                classifiers.unavailableDetectorKinds(projectId).contains(BuiltInDetector.Kind.BEHAVIOR_DRIFT),
                "off, its findings are withheld with it — a lead list for a detector you cannot "
                        + "open is worse than no lead list");

        // The alert seam: a rule pointing at this classifier stops evaluating. AlertWorker asks exactly
        // this question per rule, so a rule that survives here is a rule that can still page someone.
        String id = storedRow(projectId, "behavior_drift").id();
        assertFalse(
                classifiers.reachesProject(projectId, id),
                "an alert rule on it must stop evaluating rather than wait for its window to empty");

        // And the row itself is still there, untouched, exactly as the other cases assert.
        assertTrue(storedRow(projectId, "behavior_drift").enabled(), "history and state survive the switch");
    }

    /**
     * A flag going ON reaches a project that has <b>never sent a single trace</b>.
     *
     * <p>This is the regression the whole case exists for. Catalog provisioning used to run inside the sweep
     * heartbeat's loop over {@code projectsWithObservations()} — {@code SELECT DISTINCT project_id FROM span}
     * — which quietly made "which classifiers does this org have" a function of trace ingestion. A project
     * with zero spans was not in that scan at all, so turning a capability on for it did nothing whatsoever:
     * the org sat on the platform-default catalog until its first production span landed, at which point the
     * missing classifiers appeared within a heartbeat with nobody having touched anything. That is
     * indistinguishable, from outside, from a feature flag that takes a day to propagate.
     *
     * <p>So the test drives {@link ClassifierCatalogWorker#tick()} rather than {@code resyncBuiltIns}: the bug
     * was never in what a resync does to one project, it was in which projects were handed to it. Asserting
     * mid-test that the project is absent from {@code projectsWithObservations()} pins that — the old scope
     * would skip this project, and the new one reaches it because it asks the project table instead.
     */
    @Test
    void flagOnReachesAProjectWithNoTracesAtAll() {
        // behavior_drift is off by DEFAULT in an open build, which is what keeps this premise reachable: the
        // creation-time ClassifierSeedListener cannot seed it, so the only thing that can make the row appear
        // later is the periodic reconcile. (This case used to pre-set a flag OFF before bootstrapping the org.
        // It cannot any more — an override is a row keyed by an org id, and the org does not exist yet.)
        var fix = TenantFixture.bootstrap(tenants, "catalog-no-traces");
        String projectId = fix.project().id();
        assertFalse(visibleKeys(projectId).contains("behavior_drift"), "off at creation, never seeded");

        grant(fix.org().id(), Capability.BEHAVIOR_DRIFT); // the org buys it — nothing else changes
        assertFalse(
                substrate.projectsWithObservations().contains(projectId),
                "the project has never ingested a span, which used to make it invisible to provisioning");

        catalogWorker.tick();

        assertTrue(
                visibleKeys(projectId).contains("behavior_drift"),
                "the reconcile scans the PROJECT table, so a quiet project gets the classifier its org now "
                        + "has — no trace required, and on the reconcile's own cadence rather than whenever "
                        + "the project's first span happens to arrive");
        assertTrue(storedRow(projectId, "behavior_drift").enabled(), "seeded ON, like every built-in");
    }

    /**
     * The risk-7 discriminator, and the reason it is written as a test rather than only as a comment: if a
     * future edit ever expresses "flagged off" by disabling the row — the one-line change that merges this
     * path into {@code retireDroppedBuiltIns} — this assertion is what fails. It asserts the row is still
     * {@code enabled} after the flag went off AND after a resync ran the retirement path over it, which is
     * exactly what a withdrawal would have destroyed.
     */
    @Test
    void flagOffNeverWritesToTheRow() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-nowrite");
        String projectId = fix.project().id();
        grant(fix.org().id(), Capability.GROUNDEDNESS);
        classifiers.seedBuiltIns(projectId);

        String id = storedRow(projectId, "groundedness").id();
        assertTrue(storedRow(projectId, "groundedness").enabled(), "every built-in seeds enabled");

        withhold(fix.org().id(), Capability.GROUNDEDNESS);
        classifiers.resyncBuiltIns(projectId); // seeds, then runs retireDroppedBuiltIns over every row

        ClassifierRow stored = storedRow(projectId, "groundedness");
        assertEquals(id, stored.id(), "the row is neither deleted nor re-inserted under a new id");
        assertTrue(stored.builtIn(), "and is still a built-in");
        assertTrue(
                stored.enabled(),
                "and is still ENABLED — turning a capability off suppresses, it does not withdraw. Retirement "
                        + "is the only path allowed to disable a row, and it keys on catalog membership alone.");
    }

    /**
     * The other half of the same invariant: because withholding never wrote, turning it back on restores the
     * <b>project's</b> switch position rather than the catalog's seed default. The project below disables a
     * classifier that seeds enabled, so "restored" and "re-seeded" give different answers and the assertion
     * can tell them apart.
     */
    @Test
    void switchingBackOnRestoresTheProjectsOwnSwitch() {
        var fix = TenantFixture.bootstrap(tenants, "catalog-restore");
        String projectId = fix.project().id();
        grant(fix.org().id(), Capability.MALFORMED_OUTPUT);
        classifiers.seedBuiltIns(projectId);

        // The project makes its OWN decision — malformed_output seeds enabled, and this project turns it off.
        // That is the second of the two stacked questions; the capability layer only ever answers the first.
        String id = storedRow(projectId, "malformed_output").id();
        classifiers.setEnabled(projectId, id, false);

        withhold(fix.org().id(), Capability.MALFORMED_OUTPUT);
        assertFalse(visibleKeys(projectId).contains("malformed_output"), "gone while it is off");

        // Cleared rather than set back to true, so this asserts the DEFAULT restores it, not a second row.
        clear(fix.org().id(), Capability.MALFORMED_OUTPUT);
        assertTrue(visibleKeys(projectId).contains("malformed_output"), "coming back on restores it");
        assertFalse(
                classifiers.get(projectId, id).enabled(),
                "with the project's own disabled state intact, not reset to the catalog's seeded default");
    }
}

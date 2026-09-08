// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.ClassifierRow;
import ai.tessary.evals.classifier.TestObjectProvider;
import ai.tessary.evals.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.evals.classifier.detector.EncoderScorer;
import ai.tessary.evals.classifier.metric.MetricDriftConfig;
import ai.tessary.evals.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.evals.classifier.substrate.SubstrateReadRepository;
import ai.tessary.evals.pipeline.CallSiteFact;
import ai.tessary.evals.plan.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The catalog is derived from the {@link ClassifierModelModule} manifests: the built-in list and the
 * detector-dispatch map both come from the module declarations, and the dispatch-only user-regex
 * detector is registered alongside. Pins the manifest-driven wiring so a
 * future module edit that drops a classifier or its detector fails loudly.
 */
class ClassifierModelModuleCatalogTest {

    /**
     * The catalog every other test in this file builds against, with ONE discovered {@link
     * DetectorSupplier} stubbed in for {@code groundedness} — standing in for {@code
     * tessary-paid/groundedness}'s {@code GroundednessAutoConfiguration}, which is what supplies the
     * real detector in a running backend since #887/#888. Stubbed rather than real: the actual
     * detector class now lives in the paid overlay, outside this module's test classpath, and this
     * file's job is to pin the CATALOG's wiring, not re-prove the detector's own behaviour (that lives
     * in {@code tessary-paid/groundedness}'s relocated {@code GroundednessDetectorTest}). The stub
     * answers {@code callSiteFactsRead()} with the real detector's declared set so {@link
     * #callSiteFactsAreDeclaredByExactlyTheDetectorsGatedOnThem} still exercises a true fact.
     */
    private BuiltInClassifierCatalog catalog() {
        BuiltInDetector groundednessStub = Mockito.mock(BuiltInDetector.class);
        Mockito.when(groundednessStub.kind()).thenReturn(BuiltInDetector.Kind.GROUNDEDNESS);
        Mockito.when(groundednessStub.callSiteFactsRead()).thenReturn(Set.of(CallSiteFact.SHAPE));
        return catalogWithDiscovered(deps -> groundednessStub);
    }

    /**
     * A catalog built with whatever {@link DetectorSupplier}s the test wants to prove something about
     * the discovery seam itself — the two tests below construct one directly rather than going through
     * {@link #catalog()}'s groundedness stub, since they are pinning the seam's own contract (no
     * membership guard, fail-loud on a duplicate kind) rather than the shipped catalog's shape.
     */
    private BuiltInClassifierCatalog catalogWithDiscovered(DetectorSupplier... discovered) {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                Mockito.mock(SubstrateReadRepository.class),
                Mockito.mock(EncoderScorer.class),
                Mockito.mock(ConversationThreadAssembler.class),
                TestObjectProvider.of(discovered));
    }

    @Test
    void discoveredSupplierNeedsNoInTreeManifestEntry() {
        // The generic seam has NO membership check against MODULES (#887/#888's fork decision):
        // a DetectorSupplier claiming a kind the catalog manifest never declared is folded into the
        // dispatch map all the same. That is the trade documented on DetectorSupplier's class comment
        // — such a detector builds and registers, but nothing ever routes a sweep to it, because
        // seeding, the capability gate and grainFor all still read MODULES independently of this seam.
        BuiltInDetector stub = Mockito.mock(BuiltInDetector.class);
        Mockito.when(stub.kind()).thenReturn("example_kind");
        BuiltInClassifierCatalog catalog = catalogWithDiscovered(deps -> stub);
        assertNotNull(
                catalog.detectorFor("example_kind"),
                "a discovered DetectorSupplier is folded in even for a kind MODULES never declared");
    }

    @Test
    void discoveredSupplierCollidingWithAnInTreeKindFailsLoud() {
        // The same invariant ClassifierSweepRegistry enforces for ClassifierSweep, now proven for
        // DetectorSupplier too: two sources claiming one kind — here, a discovered supplier colliding
        // with secret_leak's in-tree MODULES factory, which is never nulled — means two analyses would
        // write findings under one classifier row, and the constructor must fail rather than let
        // classpath order pick a silent winner.
        BuiltInDetector discovered = Mockito.mock(BuiltInDetector.class);
        Mockito.when(discovered.kind()).thenReturn(BuiltInDetector.Kind.SECRET_LEAK);
        assertThrows(
                IllegalStateException.class,
                () -> catalogWithDiscovered(deps -> discovered),
                "a discovered supplier claiming a kind an in-tree module already wires must fail loud");
    }

    @Test
    void everyBuiltInHasAWiredDetector() {
        BuiltInClassifierCatalog catalog = catalog();
        List<BuiltInClassifierCatalog.BuiltIn> builtIns = catalog.builtIns();
        assertEquals(
                9,
                builtIns.size(),
                "nine built-in classifiers ship (four observation/turn-grain + five fitting-tier: "
                        + "behaviour drift, duration drift, cost drift, tool errors and SOP conformance)");
        for (BuiltInClassifierCatalog.BuiltIn b : builtIns) {
            Grain grain = catalog.grainFor(b.detector());
            if (Grain.TRACE == grain || Grain.WINDOW == grain) {
                // The fitting tier: these ship as per-project fitting procedures rather than models, so
                // they contribute catalog metadata and no BuiltInDetector. Trace-grain behaviour drift
                // implements TrajectoryDetector and is dispatched by BehaviorDriftSweep; the two
                // window-grain metric classifiers are dispatched by MetricDriftSweep; window-grain SOP
                // conformance and tool errors are peeled off ahead of it to their own sweeps.
                assertNull(
                        catalog.detectorFor(b.detector()), b.classifierKey() + " is fitting-tier: no BuiltInDetector");
                continue;
            }
            assertNotNull(
                    catalog.detectorFor(b.detector()),
                    "built-in " + b.classifierKey() + " must have a detector wired from its manifest");
        }
    }

    /**
     * Duration drift's operating point, and the one guard left in front of it.
     *
     * <p>This test used to assert the classifier seeded DISABLED, derived from an {@code EXPERIMENTAL}
     * lifecycle. That axis is gone: a classifier we do not trust is one we do not flag on outside our own
     * orgs, and expressing the same intent twice meant two places to get it wrong. So the only thing
     * standing between {@code w1_floor = 0.139} — a 15% move, set from a synthetic null run, which
     * {@code classifiers/metric_drift/PLAN.md} §9's run against real traffic has not yet replaced — and a
     * partner's Triage is {@code duration_drift_enabled}.
     *
     * <p><b>That guard is a console setting and cannot be asserted here</b>, which is worth stating
     * plainly rather than leaving as a gap someone discovers later. What this test still pins is that the
     * classifier declares a capability at all, so it is governed by a flag rather than ungoverned, and
     * that the numbers below are the ones the sweep will actually read.
     */
    @Test
    void durationDriftIsFlagGovernedAndCarriesItsMeasures() {
        BuiltInClassifierCatalog.BuiltIn durationDrift = builtIn(catalog().builtIns(), "duration_drift");
        assertEquals(
                Capability.DURATION_DRIFT,
                durationDrift.capability(),
                "the flag is now the only thing holding an unmeasured detector back — it must exist");
        // The measures ride in the config blob rather than being separate modules — that is what makes
        // ONE switch govern both grains of the same question (PROGRAM.md §3.1): a turn's own duration and
        // the duration of each tool call inside it are two halves of one thing a human decides about.
        String config = configOf(catalog().builtIns(), "duration_drift");
        assertNotNull(config);
        assertTrue(config.contains("\"measures\":[\"turn_duration\",\"tool_duration\"]"), config);
        assertTrue(config.contains("\"w1_floor\":0.139"), config);
        // Shipping tool_duration without this dial would ship the second grain without the §6.1 rule that
        // keeps one event to one finding — two rows, in a stream with no alert budget to absorb the second.
        assertTrue(config.contains("\"explained_by_fraction\":0.5"), config);
    }

    /**
     * Cost drift is the second switch, and the token buckets are deliberately NOT under it as measures.
     *
     * <p>Only measures that can OPEN a finding are listed in {@code measures}, because that list is what
     * {@code MetricDriftConfig}'s registry resolves against — so PROGRAM.md §6.1's rule ("the four token
     * buckets are evidence, never findings") is enforced by the config rather than remembered by whoever
     * edits it next. A prompt edit that kills caching moves cost, input tokens and cache reads at once;
     * naming them here would turn that one cause into five rows in a stream with no alert budget.
     *
     * <p>Like duration drift, it is held back only by {@code cost_drift_enabled} — the same unmeasured
     * {@code w1_floor}, and the same guard that lives in a console rather than in this test.
     */
    @Test
    void costDriftIsASecondSwitchWhoseTokenBucketsAreEvidenceOnly() {
        BuiltInClassifierCatalog.BuiltIn costDrift = builtIn(catalog().builtIns(), "cost_drift");
        assertEquals(Capability.COST_DRIFT, costDrift.capability(), "governed by its own flag, not duration's");

        String config = configOf(catalog().builtIns(), "cost_drift");
        assertNotNull(config);
        assertTrue(config.contains("\"measures\":[\"cost\"]"), config);
        for (String bucket : List.of("tok_input", "tok_output", "tok_cache_read", "tok_cache_write")) {
            assertFalse(
                    config.contains(bucket), bucket + " is evidence on the cost finding, never a measure: " + config);
        }
        // Cost SUMS over a trace's spans, so it waits for them; duration is read off one span whose
        // arrival is its own completion signal and ignores this. Two paths, deliberately (PROGRAM.md §5).
        assertTrue(config.contains("\"settle_seconds\":300"), config);
    }

    /**
     * SOP conformance is flag-governed like every built-in — it seeds ENABLED, and the only thing
     * between it and an org is {@code sop_conformance_enabled}, which is targeted on for NOBODY yet
     * (both named enablement blockers — the classify-service {@code /embed} endpoint and
     * intent-at-ingest — have since landed; what holds it off now is that no per-project bundle
     * exists until one is deployed, and the compile worker behind that is still a stub). Its config keys must also
     * actually bind — this repo's known bug class is a config_json key that silently no-ops because
     * nothing asserts it reaches what it configures (it survived three green {@code task check}s in
     * a row once). What is asserted HERE is the half that belongs to the catalog: the flag, the
     * fitting-tier shape, the seeded version, and that {@code measures} reaches
     * {@code MetricDriftConfig} as the EXPLICIT empty list, because this module is WINDOW-grain and an
     * ABSENT measures list falls back to the duration measures, which would open duration findings
     * under the conformance switch.
     *
     * <p><b>The other half moved, and on purpose.</b> The assertions that the drift knobs reach
     * {@code ConformanceConfig} live in {@code ConformanceCatalogConfigTest}, inside the package they
     * are about — which #841 took to the paid overlay, so this file may not even spell its
     * fully-qualified name any more: {@code scripts/check-open-boundary.sh} rule 1 is a text grep over
     * every open source file and does not exempt javadoc. This test is in the OPEN catalog package and
     * the enforcer bans an open module from ever declaring a dependency on a paid jar — test scope
     * included — so an assertion here that names a conformance type is one that could not have survived
     * the extraction by being re-pointed. It travelled with the directory, and it is worth keeping.
     */
    @Test
    void sopConformanceIsFlagGovernedAndItsConfigKeysBind() {
        BuiltInClassifierCatalog catalog = catalog();
        BuiltInClassifierCatalog.BuiltIn conformance = builtIn(catalog.builtIns(), "sop_conformance");
        assertEquals(
                Capability.SOP_CONFORMANCE,
                conformance.capability(),
                "the flag is the only thing between an inert-by-design classifier and an org — it must exist");
        // Fitting-tier shape: WINDOW grain, no BuiltInDetector to dispatch.
        assertEquals(Grain.WINDOW, catalog.grainFor(BuiltInDetector.Kind.SOP_CONFORMANCE));
        assertNull(catalog.detectorFor(BuiltInDetector.Kind.SOP_CONFORMANCE));

        String config = configOf(catalog.builtIns(), "sop_conformance");
        assertNotNull(config);
        ObjectMapper mapper = new ObjectMapper();

        // The seeded blob's own text, which the catalog owns and can assert without naming a
        // conformance type. That the values PARSE into ConformanceConfig is ConformanceCatalogConfigTest's.
        assertTrue(config.contains("\"alpha\":0.01"), config);
        assertTrue(config.contains("\"min_activations\":30"), config);
        assertTrue(config.contains("\"settle_seconds\":300"), config);
        assertTrue(config.contains("\"drift_window_turns\":2000"), config);
        // shadow_mode (v3): a project opts into shadow, and a detector silently muted by a default
        // would be the worst version of this bug class, so the seeded blob must SURFACE.
        assertTrue(config.contains("\"shadow_mode\":false"), config);

        // Binding 2: measures is present, empty, and keeps the WINDOW dispatch inert.
        assertTrue(config.contains("\"measures\":[]"), config);
        assertTrue(
                MetricDriftConfig.of(mapper, config).measured().isEmpty(),
                "an enabled sop_conformance row must no-op in the metric-drift fold, not open metric findings");
    }

    private static BuiltInClassifierCatalog.BuiltIn builtIn(
            List<BuiltInClassifierCatalog.BuiltIn> builtIns, String key) {
        return builtIns.stream()
                .filter(b -> b.classifierKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no built-in named " + key));
    }

    @Test
    void builtInKeysAndVersionsMatchTheManifests() {
        List<BuiltInClassifierCatalog.BuiltIn> builtIns = catalog().builtIns();
        // key -> version, exactly as declared (bumped keys re-sync onto seeded projects).
        assertEquals("frustration", builtIns.get(0).classifierKey());
        // 8: the attribution gate switched on, and the user-facing description changed with it
        // because HIGH now means emotion AND agent-attribution rather than emotion alone.
        assertEquals(8, versionOf(builtIns, "frustration"));
        assertEquals(2, versionOf(builtIns, "secret_leak"));
        assertEquals(1, versionOf(builtIns, "malformed_output"));
        // 2: evidence-as-premise + per-sentence claims + the abstain filter. 3: tool results dropped
        // as evidence, so tool-backed turns are out of scope. 4: the HEAD changed — MiniCheck (binary,
        // "supported yes/no") to bart-large-mnli (three-way), so a finding now means CONTRADICTED
        // rather than "not supported", and a claim the source is silent on is exempt instead of
        // flagged. That is a change in what the classifier asserts, not a threshold move, which is
        // exactly when the user-facing description has to re-sync onto already-seeded projects.
        assertEquals(4, versionOf(builtIns, "groundedness"));
        // 2: tool_duration joined the measure list. The bump is not cosmetic — resyncBuiltIns rewrites an
        // already-seeded project's definition only when the catalog version exceeds the stored one, so
        // without it the second grain would reach fresh installs and nothing else. 3: the escalation cap
        // joined the blob. 4: it left again, once #684 made every escalation hand-pressed and
        // MetricDriftConfig dropped the component — the bump is what clears the dead key off the
        // projects already carrying it.
        assertEquals(4, versionOf(builtIns, "duration_drift"));
        // 3: the same escalation cap, joined and then removed, kept in step with duration drift's blob.
        assertEquals(3, versionOf(builtIns, "cost_drift"));
        // 4: the USER-FACING description gained the second gate annotation (gate precision
        // degraded (PACC), frozen decision 15) — description re-sync rides the same version gate
        // as the blob. 3: shadow_mode joined the blob (the validation-ladder switch). 2 was the
        // serving knobs (settle_seconds, drift_window_turns) when ConformanceSweep took over the
        // dispatch. Any future change to its config blob must bump this or already-seeded
        // projects never see it.
        assertEquals(4, versionOf(builtIns, "sop_conformance"));
        // frustration and groundedness carry a shifted operating point; the others use detector defaults.
        assertNotNull(configOf(builtIns, "frustration"));
        assertNotNull(configOf(builtIns, "groundedness"));
        assertNull(configOf(builtIns, "secret_leak"));
    }

    @Test
    void grainIsDeclaredPerClassifierAndDefaultsToObservation() {
        BuiltInClassifierCatalog catalog = catalog();
        // Frustration is the one TURN-grain built-in: its subject is the user's message, and the user
        // says it once per turn however many spans the turn fans out into.
        assertEquals(Grain.TURN, catalog.grainFor(BuiltInDetector.Kind.FRUSTRATION));
        assertEquals(Grain.TRACE, catalog.grainFor(BuiltInDetector.Kind.BEHAVIOR_DRIFT));
        // Duration drift's scored unit is a WINDOW of one bucket's traffic summarized as a distribution:
        // not a span, not a turn, not a trace. It also spans two candidate grains (turns and tool calls)
        // under one switch, which a single catalog grain could not have expressed — the per-measure grain
        // lives in MetricDriftConfig and MetricDriftSweep reads it from there.
        assertEquals(Grain.WINDOW, catalog.grainFor(BuiltInDetector.Kind.DURATION_DRIFT));
        assertEquals(Grain.WINDOW, catalog.grainFor(BuiltInDetector.Kind.COST_DRIFT));
        // The per-span classifiers: each of these genuinely evaluates one call's own output.
        assertEquals(Grain.OBSERVATION, catalog.grainFor(BuiltInDetector.Kind.SECRET_LEAK));
        assertEquals(Grain.OBSERVATION, catalog.grainFor(BuiltInDetector.Kind.MALFORMED_OUTPUT));
        assertEquals(Grain.OBSERVATION, catalog.grainFor(BuiltInDetector.Kind.GROUNDEDNESS));
        // Undeclared kinds — user-authored regex signals, and anything unknown — sweep at
        // the historical per-span grain rather than silently inheriting a narrower candidate set.
        assertEquals(Grain.OBSERVATION, catalog.grainFor(BuiltInDetector.Kind.REGEX));
        assertEquals(Grain.OBSERVATION, catalog.grainFor("no-such-detector"));
    }

    @Test
    void callSiteFactsAreDeclaredByExactlyTheDetectorsGatedOnThem() {
        BuiltInClassifierCatalog catalog = catalog();
        // The two built-ins that abstain when a code-derived call-site column is missing must SAY so:
        // ClassifierService#rewindForCallSiteFact rewinds off this declaration, and a detector that
        // reads a call-site column without declaring it silently keeps the stranded-history bug (its
        // pre-synthesis observations stay scored none() behind a cursor that only moves forward).
        assertEquals(
                Set.of(CallSiteFact.OUTPUT_SCHEMA),
                detector(catalog, BuiltInDetector.Kind.MALFORMED_OUTPUT).callSiteFactsRead());
        assertEquals(
                Set.of(CallSiteFact.SHAPE),
                detector(catalog, BuiltInDetector.Kind.GROUNDEDNESS).callSiteFactsRead());

        // Everything else reads only the observation, so nothing outside the trace can invalidate a
        // sweep of it. If a new detector starts gating on a call-site column, this assertion is the
        // one that should fail.
        //
        // The three fitting-tier classifiers are absent from this loop because they carry no
        // BuiltInDetector object to ask — there is nothing to declare a fact ON. All read observation
        // columns and the trace spine only (the metric classifiers' bucket key is
        // observation.call_site_id, an ingest fact present from the first trace, and cost reads
        // observation.usage and observation.model), so their empty declared set is stated in the module
        // comments in BuiltInClassifierCatalog rather than asserted here. Cost drift's analogous
        // late-arriving-fact hazard is the price book, which is not a call-site fact and is handled by
        // resolving rates at sweep time.
        for (String kind : List.of(
                BuiltInDetector.Kind.FRUSTRATION, BuiltInDetector.Kind.SECRET_LEAK, BuiltInDetector.Kind.REGEX)) {
            assertEquals(
                    Set.of(),
                    detector(catalog, kind).callSiteFactsRead(),
                    kind + " depends on the trace alone and must declare no call-site fact");
        }
    }

    private static BuiltInDetector detector(BuiltInClassifierCatalog catalog, String kind) {
        BuiltInDetector d = catalog.detectorFor(kind);
        assertNotNull(d, kind + " is dispatched");
        return d;
    }

    @Test
    void dispatchOnlyDetectorsAreRegisteredButNotCatalogBuiltIns() {
        BuiltInClassifierCatalog catalog = catalog();
        // user regex dispatches, but is not a built-in. (The centroid user-classifier dispatch-only
        // detector this test used to also pin here was removed with the rest of the vector substrate,
        // #1116 — Kind.CLASSIFIER no longer exists.)
        assertNotNull(catalog.detectorFor(BuiltInDetector.Kind.REGEX));
        assertEquals(
                0,
                catalog.builtIns().stream()
                        .filter(b -> b.detector().equals(BuiltInDetector.Kind.REGEX))
                        .count(),
                "regex backs user signals, not a catalog built-in");
    }

    @Test
    void frustrationShipsTheAttributionGateAndABumpedVersionToCarryIt() {
        // THE config-key-binding regression. A new key in defaultConfigJson is a silent no-op unless
        // TWO things hold: the key is actually in the default config, and the catalog version was
        // bumped — ClassifierService re-syncs a built-in onto an already-seeded project only when the
        // catalog version exceeds the stored one, so an un-bumped change reaches fresh installs only.
        // This repo has shipped that bug before; assert both halves rather than either.
        List<BuiltInClassifierCatalog.BuiltIn> builtIns = catalog().builtIns();
        String config = configOf(builtIns, "frustration");
        assertNotNull(config, "frustration ships a default config");
        assertTrue(
                config.contains("\"attribution_head\":\"attribution\""),
                "the gate names the head it scores with: " + config);
        assertTrue(config.contains("\"attribution_threshold\":0.64"), "and its operating point: " + config);
        assertTrue(
                versionOf(builtIns, "frustration") >= 8,
                "the version must advance or the gate never reaches an existing project");
    }

    @Test
    void frustrationsUserFacingDescriptionDescribesTheGate() {
        // The description is re-synced onto every seeded project by the same version bump, so it is
        // written into production rows as fact. HIGH now means emotion AND agent-attribution; a
        // description still claiming plain emotion would be actively wrong, not merely stale.
        String description = catalog().builtIns().stream()
                .filter(b -> b.classifierKey().equals("frustration"))
                .findFirst()
                .orElseThrow()
                .description();
        assertTrue(description.contains("AGENT"), "it says whose frustration this is: " + description);
        assertTrue(
                description.toLowerCase(java.util.Locale.ROOT).contains("demoted"),
                "and that a non-agent-caused turn is demoted rather than dropped: " + description);
    }

    private static int versionOf(List<BuiltInClassifierCatalog.BuiltIn> builtIns, String key) {
        return builtIns.stream()
                .filter(b -> b.classifierKey().equals(key))
                .findFirst()
                .orElseThrow()
                .version();
    }

    private static @Nullable String configOf(List<BuiltInClassifierCatalog.BuiltIn> builtIns, String key) {
        return builtIns.stream()
                .filter(b -> b.classifierKey().equals(key))
                .findFirst()
                .orElseThrow()
                .defaultConfigJson();
    }

    @Test
    void frustrationSeedsAtTheTrackingBarAndEveryOtherBuiltInStaysWide() {
        // `mode` is the operating point a signal is READ at: discovery surfaces the high+low union,
        // tracking the HIGH band alone. Discovery is the right default for a classifier nobody has
        // characterised — you cannot narrow a band you have never seen fire — and frustration is the
        // one that HAS been. Measured in production 2026-08-20: discovery surfaced 67.4% of
        // interview-coach turns against 18.6% for the high band, 28.5%/14.6% on zipeats,
        // 20.5%/3.5% on policy-gpt.
        //
        // The assertion is deliberately two-sided. Moving the others on frustration's argument would
        // be asserting a measurement nobody has made, so this fails if a future edit widens
        // frustration OR quietly narrows a built-in that has no numbers behind it.
        for (BuiltInClassifierCatalog.BuiltIn b : catalog().builtIns()) {
            String expected = "frustration".equals(b.classifierKey())
                    ? ClassifierRow.Mode.TRACKING
                    : ClassifierRow.Mode.DISCOVERY;
            assertEquals(
                    expected,
                    b.defaultMode(),
                    b.classifierKey() + " seeds at the wrong operating point — see the catalog comment. "
                            + "(The one-time migration that graduated existing tenants,"
                            + " changeset 0010 (the frustration tracking default), was a data UPDATE on `classifier`,"
                            + " not schema, so the 2026-09 baseline squash [#1074] folded it away without a"
                            + " successor file; BuiltInClassifierCatalog's TRACKING default is now the only"
                            + " place this fact lives, for new and existing tenants alike.)");
        }
    }

    @Test
    void theConvenienceConstructorDefaultsToDiscovery() {
        // Eight of nine modules use the 9-arg form and must keep seeding wide; only a module that
        // states TRACKING explicitly gets it.
        ClassifierModelModule wide = new ClassifierModelModule(
                "example", "Example", "desc", "example_kind", 1, Capability.FRUSTRATION, Grain.OBSERVATION, null, null);

        assertEquals(ClassifierRow.Mode.DISCOVERY, wide.defaultMode());
        assertEquals(ClassifierRow.Mode.DISCOVERY, wide.toBuiltIn().defaultMode());
    }
}

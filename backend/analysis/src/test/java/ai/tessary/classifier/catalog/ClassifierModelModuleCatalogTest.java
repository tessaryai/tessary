// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessDetectorSupplier;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.pipeline.CallSiteFact;
import ai.tessary.plan.Capability;
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
     * The catalog every other test in this file builds against, with the two discovered {@link
     * DetectorSupplier}s a running backend has. Frustration's is a stub: its detector is a Spring bean
     * with its own collaborators, and this file's job is to pin the catalog's wiring, not re-prove the
     * detector's own behavior. Groundedness's is the real supplier over a mocked repository, so the
     * call-site facts it declares are the shipped detector's. Every other observation-grain detector is
     * closed over in {@code MODULES}.
     */
    private BuiltInClassifierCatalog catalog() {
        BuiltInDetector frustrationStub = Mockito.mock(BuiltInDetector.class);
        Mockito.when(frustrationStub.kind()).thenReturn(BuiltInDetector.Kind.FRUSTRATION);
        Mockito.when(frustrationStub.callSiteFactsRead()).thenReturn(Set.of());
        return catalogWithDiscovered(
                deps -> frustrationStub,
                new GroundednessDetectorSupplier(Mockito.mock(GroundednessAssessmentRepository.class)));
    }

    /**
     * A catalog built with whatever {@link DetectorSupplier}s the test wants to prove something about
     * the discovery seam itself. The two tests below construct one directly rather than going through
     * {@link #catalog()}'s two suppliers, since they are pinning the seam's own contract (no
     * membership guard, fail-loud on a duplicate kind) rather than the shipped catalog's shape.
     */
    private BuiltInClassifierCatalog catalogWithDiscovered(DetectorSupplier... discovered) {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                Mockito.mock(SubstrateReadRepository.class),
                Mockito.mock(EncoderScorer.class),
                TestObjectProvider.of(discovered));
    }

    @Test
    void discoveredSupplierCollidingWithAnInTreeKindFailsLoud() {
        // The same invariant ClassifierSweepRegistry enforces for ClassifierSweep, now proven for
        // DetectorSupplier too: two sources claiming one kind (here, a discovered supplier colliding
        // with secret_leak's in-tree MODULES factory, which is never nulled) means two analyses would
        // write findings under one classifier row, and the constructor must fail rather than let
        // classpath order pick a silent winner.
        BuiltInDetector discovered = Mockito.mock(BuiltInDetector.class);
        Mockito.when(discovered.kind()).thenReturn(BuiltInDetector.Kind.SECRET_LEAK);
        assertThrows(
                IllegalStateException.class,
                () -> catalogWithDiscovered(deps -> discovered),
                "a discovered supplier claiming a kind an in-tree module already wires must fail loud");
    }

    /**
     * Duration drift's operating point, and the one guard left in front of it.
     *
     * <p>The only thing standing between {@code w1_floor = 0.139} (a 15% move, set from a synthetic
     * null run) and a partner's Triage is {@code duration_drift_enabled}.
     *
     * <p>That guard is a console setting and cannot be asserted here, which is worth stating
     * plainly rather than leaving as a gap someone discovers later. What this test still pins is
     * that the classifier declares a capability at all, so it is governed by a flag rather than
     * ungoverned, and that the numbers below are the ones the sweep will actually read.
     */
    @Test
    void durationDriftIsFlagGovernedAndCarriesItsMeasures() {
        BuiltInClassifierCatalog.BuiltIn durationDrift = builtIn(catalog().builtIns(), "duration_drift");
        assertEquals(
                Capability.DURATION_DRIFT,
                durationDrift.capability(),
                "the flag is now the only thing holding an unmeasured detector back — it must exist");
        // The measures ride in the config blob rather than being separate modules: that is what
        // makes one switch govern both grains of the same question, a turn's own duration and the
        // duration of each tool call inside it being two halves of one thing a human decides about.
        String config = configOf(catalog().builtIns(), "duration_drift");
        assertNotNull(config);
        assertTrue(config.contains("\"measures\":[\"turn_duration\",\"tool_duration\"]"), config);
        assertTrue(config.contains("\"w1_floor\":0.139"), config);
        // Shipping tool_duration without this dial would ship the second grain without the rule that
        // keeps one event to one finding: two rows, in a stream with no alert budget to absorb the second.
        assertTrue(config.contains("\"explained_by_fraction\":0.5"), config);
    }

    /**
     * Cost drift is the second switch, and the token buckets are deliberately not under it as
     * measures.
     *
     * <p>Only measures that can open a finding are listed in {@code measures}, because that list is
     * what {@code MetricDriftConfig}'s registry resolves against, so the rule that the four token
     * buckets are evidence, never findings, is enforced by the config rather than remembered by
     * whoever edits it next. A prompt edit that kills caching moves cost, input tokens and cache
     * reads at once; naming them here would turn that one cause into five rows in a stream with no
     * alert budget.
     *
     * <p>Like duration drift, it is held back only by {@code cost_drift_enabled}: the same
     * unmeasured {@code w1_floor}, and the same guard that lives in a console rather than in this
     * test.
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
        // Cost sums over a trace's spans, so it waits for them; duration is read off one span whose
        // arrival is its own completion signal and ignores this. Two paths, deliberately.
        assertTrue(config.contains("\"settle_seconds\":300"), config);
    }

    private static BuiltInClassifierCatalog.BuiltIn builtIn(
            List<BuiltInClassifierCatalog.BuiltIn> builtIns, String key) {
        return builtIns.stream()
                .filter(b -> b.classifierKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no built-in named " + key));
    }

    @Test
    void callSiteFactsAreDeclaredByExactlyTheDetectorsGatedOnThem() {
        BuiltInClassifierCatalog catalog = catalog();
        // The two built-ins that abstain when a code-derived call-site column is missing must say
        // so: ClassifierService#rewindForCallSiteFact rewinds off this declaration, and a detector
        // that reads a call-site column without declaring it silently keeps the stranded-history bug
        // (its pre-synthesis observations stay scored none() behind a cursor that only moves forward).
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
        // BuiltInDetector object to ask: there is nothing to declare a fact on. All read observation
        // columns and the trace spine only (the metric classifiers' bucket key is
        // observation.call_site_id, an ingest fact present from the first trace, and cost reads
        // observation.usage and observation.model), so their empty declared set is stated in the
        // module comments in BuiltInClassifierCatalog rather than asserted here. Cost drift's
        // analogous late-arriving-fact hazard is the price book, which is not a call-site fact and
        // is handled by resolving rates at sweep time.
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
    void frustrationAndGroundednessSeedDisabledAndNoOtherBuiltInDoes() {
        // Enabling frustration spends the org's own provider credit, and groundedness needs a model server
        // set up first, so a person turns each on. Every other built-in seeds enabled.
        for (BuiltInClassifierCatalog.BuiltIn b : catalog().builtIns()) {
            assertEquals(
                    !Set.of("frustration", "groundedness").contains(b.classifierKey()),
                    b.defaultEnabled(),
                    b.classifierKey() + " seeds with the wrong switch");
        }
    }

    private static @Nullable String configOf(List<BuiltInClassifierCatalog.BuiltIn> builtIns, String key) {
        return builtIns.stream()
                .filter(b -> b.classifierKey().equals(key))
                .findFirst()
                .orElseThrow()
                .defaultConfigJson();
    }

    @Test
    void frustrationAndGroundednessSeedAtTheTrackingBarAndEveryOtherBuiltInStaysWide() {
        // `mode` is the operating point a signal is read at: discovery surfaces the high+low union,
        // tracking the high band alone. Discovery is the right default for a classifier nobody has
        // characterized, since you cannot narrow a band you have never seen fire. Frustration and
        // groundedness each write one band, at a threshold set on labelled data, so both read the same
        // rows in either mode and seed at tracking.
        //
        // The assertion is deliberately two-sided: it fails if a future edit widens either of them or
        // quietly narrows a built-in that has no numbers behind it.
        for (BuiltInClassifierCatalog.BuiltIn b : catalog().builtIns()) {
            String expected = Set.of("frustration", "groundedness").contains(b.classifierKey())
                    ? ClassifierRow.Mode.TRACKING
                    : ClassifierRow.Mode.DISCOVERY;
            assertEquals(
                    expected,
                    b.defaultMode(),
                    b.classifierKey() + " seeds at the wrong operating point — see the catalog comment. "
                            + "(The one-time migration that graduated existing tenants,"
                            + " changeset 0010 (the frustration tracking default), was a data UPDATE on `classifier`,"
                            + " not schema, so the 2026-09 baseline squash folded it away without a"
                            + " successor file; BuiltInClassifierCatalog's TRACKING default is now the only"
                            + " place this fact lives, for new and existing tenants alike.)");
        }
    }
}

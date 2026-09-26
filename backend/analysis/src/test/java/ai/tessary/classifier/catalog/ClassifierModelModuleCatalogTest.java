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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The catalog is derived from the {@link ClassifierModelModule} manifests, so a module edit that drops a classifier
 * or its detector fails here.
 */
class ClassifierModelModuleCatalogTest {

    /**
     * The shipped catalog with the two discovered suppliers: a stubbed frustration detector (a Spring bean elsewhere)
     * and the real groundedness supplier over a mocked repository, so its declared call-site facts are the shipped
     * ones.
     */
    private BuiltInClassifierCatalog catalog() {
        BuiltInDetector frustrationStub = Mockito.mock(BuiltInDetector.class);
        Mockito.when(frustrationStub.kind()).thenReturn(BuiltInDetector.Kind.FRUSTRATION);
        Mockito.when(frustrationStub.callSiteFactsRead()).thenReturn(Set.of());
        return catalogWithDiscovered(
                deps -> frustrationStub,
                new GroundednessDetectorSupplier(Mockito.mock(GroundednessAssessmentRepository.class)));
    }

    /** A catalog with chosen suppliers, for pinning the discovery seam's own contract. */
    private BuiltInClassifierCatalog catalogWithDiscovered(DetectorSupplier... discovered) {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                Mockito.mock(SubstrateReadRepository.class),
                Mockito.mock(EncoderScorer.class),
                TestObjectProvider.of(discovered));
    }

    @Test
    void discoveredSupplierCollidingWithAnInTreeKindFailsLoud() {
        // Two sources claiming one kind would have two analyses write under one classifier row, so the constructor
        // fails rather than letting classpath order pick a winner.
        BuiltInDetector discovered = Mockito.mock(BuiltInDetector.class);
        Mockito.when(discovered.kind()).thenReturn(BuiltInDetector.Kind.SECRET_LEAK);
        assertThrows(
                IllegalStateException.class,
                () -> catalogWithDiscovered(deps -> discovered),
                "a discovered supplier claiming a kind an in-tree module already wires must fail loud");
    }

    /**
     * Both drift classifiers are governed by their own flag, the only guard left in front of an unmeasured
     * {@code w1_floor} (0.139, from a synthetic null run); the flag itself is a console setting. Each lists
     * only measures that can open a finding: duration's two grains under one switch, and cost alone, with the
     * token buckets as evidence, so one prompt edit that kills caching is one row, not five.
     */
    @Test
    void driftClassifiersAreFlagGovernedAndListOnlyMeasuresThatCanOpenAFinding() {
        BuiltInClassifierCatalog.BuiltIn durationDrift = builtIn(catalog().builtIns(), "duration_drift");
        assertEquals(
                Capability.DURATION_DRIFT,
                durationDrift.capability(),
                "the flag is now the only thing holding an unmeasured detector back — it must exist");
        String duration = durationDrift.defaultConfigJson();
        assertNotNull(duration);
        assertTrue(duration.contains("\"measures\":[\"turn_duration\",\"tool_duration\"]"), duration);
        assertTrue(duration.contains("\"w1_floor\":0.139"), duration);
        // Keeps one event to one finding across the two grains.
        assertTrue(duration.contains("\"explained_by_fraction\":0.5"), duration);

        BuiltInClassifierCatalog.BuiltIn costDrift = builtIn(catalog().builtIns(), "cost_drift");
        assertEquals(Capability.COST_DRIFT, costDrift.capability(), "governed by its own flag, not duration's");
        String cost = costDrift.defaultConfigJson();
        assertNotNull(cost);
        assertTrue(cost.contains("\"measures\":[\"cost\"]"), cost);
        for (String bucket : List.of("tok_input", "tok_output", "tok_cache_read", "tok_cache_write")) {
            assertFalse(cost.contains(bucket), bucket + " is evidence on the cost finding, never a measure: " + cost);
        }
        // Cost sums a trace's spans, so it waits for them; duration reads one span and does not.
        assertTrue(cost.contains("\"settle_seconds\":300"), cost);
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
        // ClassifierService#rewindForCallSiteFact rewinds off this declaration; a detector gating on a
        // call-site column without declaring it keeps its pre-synthesis history stranded behind the cursor.
        assertEquals(
                Set.of(CallSiteFact.OUTPUT_SCHEMA),
                detector(catalog, BuiltInDetector.Kind.MALFORMED_OUTPUT).callSiteFactsRead());
        assertEquals(
                Set.of(CallSiteFact.SHAPE),
                detector(catalog, BuiltInDetector.Kind.GROUNDEDNESS).callSiteFactsRead());

        // Everything else reads only the trace. The fitting-tier classifiers have no BuiltInDetector to ask;
        // their empty set is stated in BuiltInClassifierCatalog's module comments.
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

    /**
     * Frustration spends the org's provider credit and groundedness needs a model server, so a person turns
     * each on; both write one band at a labelled threshold, so they seed at tracking. Every other built-in
     * seeds enabled and at discovery, since a band never seen firing cannot be narrowed. Two-sided on
     * purpose. BuiltInClassifierCatalog is the only place the tracking default lives: the 0010 data UPDATE
     * that graduated existing tenants was folded away by the 2026-09 baseline squash.
     */
    @Test
    void frustrationAndGroundednessSeedDisabledAtTrackingAndEveryOtherBuiltInSeedsOnAndWide() {
        for (BuiltInClassifierCatalog.BuiltIn b : catalog().builtIns()) {
            boolean personGated = Set.of("frustration", "groundedness").contains(b.classifierKey());
            assertEquals(!personGated, b.defaultEnabled(), b.classifierKey() + " seeds with the wrong switch");
            assertEquals(
                    personGated ? ClassifierRow.Mode.TRACKING : ClassifierRow.Mode.DISCOVERY,
                    b.defaultMode(),
                    b.classifierKey() + " seeds at the wrong operating point");
        }
    }
}

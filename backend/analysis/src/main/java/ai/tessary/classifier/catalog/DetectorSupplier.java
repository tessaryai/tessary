// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;

/**
 * A {@link BuiltInDetector} contributed from OFF the classpath's in-tree {@link
 * BuiltInClassifierCatalog#MODULES} manifest — the seam that makes observation/turn-grain detector
 * dispatch a Spring collection injection point, the same shape {@code ClassifierSweep} already has for
 * trace/window grain.
 *
 * <h2>Why this exists, and why it looks like {@code ClassifierModelModule.DetectorFactory}</h2>
 *
 * <p>Before #887/#888 there was no injection point for {@link BuiltInDetector} at all:
 * {@link BuiltInClassifierCatalog}'s constructor built its detector map from {@code MODULES}' own
 * {@code DetectorFactory} lambdas plus two hardcoded dispatch-only instances, and nothing on the
 * classpath could add a third source. That is why {@code docs/reference/classifier-extension-interface.md}
 * used to say this port was closed to an out-of-tree jar — it was, structurally, not by policy. Moving
 * {@code GroundednessDetector} to {@code tessary-paid/groundedness} needed a real dispatch path for its
 * factory lambda to live behind, since a compile-time {@code new GroundednessDetector(...)} inside the
 * open, always-compiled {@code MODULES} list cannot survive the class leaving the open tree.
 *
 * <p>This interface is deliberately its own type rather than a reuse of {@link
 * ClassifierModelModule.DetectorFactory}: a {@code DetectorFactory} is a manifest FIELD, closed over by
 * value inside a {@code static final} list built once; a {@code DetectorSupplier} is a discovered BEAN,
 * collected at construction time from whatever is on the classpath. Sharing one type would blur which
 * question is being answered — "what does this in-tree manifest entry build" versus "what does this
 * classpath additionally contribute" — for no code saved, since the shapes already coincide.
 *
 * <h2>Generic and unguarded, on purpose</h2>
 *
 * <p><b>There is no membership check against {@link BuiltInClassifierCatalog#MODULES}.</b> Any kind a
 * {@code DetectorSupplier} bean claims is folded into the dispatch map, including one {@code MODULES}
 * never declares. That is a deliberate trade, not an oversight, and it has a sharp edge documented in
 * full at {@code docs/reference/classifier-extension-interface.md} §2: a detector wired for a kind with
 * no catalog entry is dispatched to NOTHING, because seeding, the capability gate and
 * {@code grainFor}'s OBSERVATION/TURN routing all read {@code MODULES} independently of this seam. A
 * supplier claiming an undeclared kind builds successfully, registers successfully, and never fires —
 * the exact silent-failure shape the old "not open to an out-of-tree jar" warning was about, now a live
 * possibility instead of an impossibility.
 *
 * <p><b>Fails loud on a duplicate kind</b>, the same invariant {@code ClassifierSweepRegistry} enforces
 * for {@code ClassifierSweep}: two sources — in-tree or discovered, in any combination — claiming one
 * kind is two analyses writing findings under one classifier row, and {@link
 * BuiltInClassifierCatalog}'s {@code toUnmodifiableMap} throws {@link IllegalStateException} rather than
 * letting classpath order pick a silent winner.
 *
 * <p>Groundedness is the worked example: its {@code ClassifierModelModule} entry stays in-tree —
 * catalog metadata only, {@code detectorFactory} now {@code null} — and {@code
 * tessary-paid/groundedness}'s {@code GroundednessAutoConfiguration} supplies the real detector through
 * this seam.
 */
@FunctionalInterface
public interface DetectorSupplier {

    /** Builds the detector this supplier contributes, from the shared, catalog-owned dependencies. */
    BuiltInDetector build(Deps deps);
}

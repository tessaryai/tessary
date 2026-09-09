// SPDX-License-Identifier: Apache-2.0
/**
 * The shared findings and triage feature: the store every classifier writes its leads into, the
 * Layer-2 pipeline that rules on them, and the read surface that renders both.
 *
 * <h2>The seam contract</h2>
 *
 * <p>Four rules:
 *
 * <ol>
 *   <li><b>Ports live here, and adapters are discovered rather than referenced.</b> A port is
 *       injected as a Spring {@code List<T>}, ordered by {@code @Order}, or as {@code
 *       Optional<T>}. No port declares a classifier's own type in its signature.
 *       {@link ai.tessary.classifier.finding.TriageSource} and
 *       {@link ai.tessary.classifier.finding.CauseResolver} are the two.
 *   <li><b>Ports key on the open {@code BuiltInDetector.Kind} constants</b>, which stay constant.
 *       {@code BuiltInClassifierCatalog.builtIns()} is never filtered by availability:
 *       {@code ClassifierService.retireDroppedBuiltIns} keys on catalog membership, and
 *       retirement is irreversible, so a classifier dropping out of the catalog would silently
 *       retire a customer's.
 *   <li><b>An absent adapter degrades to what the surface already shows for a classifier with no
 *       data</b>: an empty list, a null block, a 404, never a wiring failure and never a 500.
 *   <li><b>Wire records are open; their factories are adapter-side.</b>
 *       {@link ai.tessary.classifier.finding.BehaviorReadiness} stays open even where its
 *       factory, {@code BehaviorProfileViews.readiness(...)}, reads types that cannot: the
 *       record/factory split holds for it alone.
 * </ol>
 *
 * <p>The prefixed names ({@code BehaviorDtos}, {@code BehaviorTriage*}) are wire lineage, not
 * package membership. The paths, controller names, method names and record simple names are
 * pinned by {@code OpenApiSpecDriftTest}; renaming them is a deliberate wire decision, not a side
 * effect of moving a package.
 */
package ai.tessary.classifier.finding;

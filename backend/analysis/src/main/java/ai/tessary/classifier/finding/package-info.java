// SPDX-License-Identifier: Apache-2.0
/**
 * The shared findings and triage feature: the store every classifier writes its leads into, the
 * Layer-2 pipeline that rules on them, and the read surface that renders both.
 *
 * <h2>The seam contract</h2>
 *
 * <p>Three rules:
 *
 * <ol>
 *   <li><b>Ports live here, and adapters are discovered rather than referenced.</b> A port is
 *       injected as a Spring {@code List<T>}, ordered by {@code @Order}, or through an
 *       {@code ObjectProvider<T>} when the open edition ships no implementation. No port declares a
 *       classifier's own type in its signature. There are two:
 *       {@link ai.tessary.classifier.finding.TriageSource}, and
 *       {@link ai.tessary.classifier.finding.FindingAdapter}, which consumers reach through
 *       {@link ai.tessary.classifier.finding.FindingAdapters} only.
 *   <li><b>{@code TriageSource} keys on the open {@code BuiltInDetector.Kind} constants</b>, which
 *       stay constant. {@code BuiltInClassifierCatalog.builtIns()} is never filtered by
 *       availability: {@code ClassifierService.retireDroppedBuiltIns} keys on catalog membership,
 *       and retirement is irreversible, so a classifier dropping out of the catalog would silently
 *       retire a customer's. <b>{@code FindingAdapter} keys on a predicate over the finding's
 *       claim</b> instead, because a paid classifier's key is per row ({@code custom-<ulid>}) and no
 *       constant names it. The predicate answers the same for every finding of one project and
 *       classifier key.
 *   <li><b>An absent adapter degrades to what the surface already shows for a classifier with no
 *       data</b>: an empty list, a null block, a 404, never a wiring failure and never a 500. With
 *       no {@code FindingAdapter}, a finding no built-in rules on is not escalated, has no
 *       {@code method.md}, and is ruled exactly as before.
 * </ol>
 *
 * <p>The prefixed names ({@code BehaviorDtos}, {@code BehaviorTriage*}) are wire lineage, not
 * package membership. The paths, controller names, method names and record simple names are
 * pinned by {@code OpenApiSpecDriftTest}; renaming them is a deliberate wire decision, not a side
 * effect of moving a package.
 */
package ai.tessary.classifier.finding;

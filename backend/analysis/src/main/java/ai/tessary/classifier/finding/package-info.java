// SPDX-License-Identifier: Apache-2.0
/**
 * The shared findings and triage feature — the store every classifier writes its leads into, the
 * Layer-2 pipeline that rules on them, and the read surface that renders both.
 *
 * <p>It used to live in {@code classifier/behavior/} because behaviour drift shipped first and the
 * generic parts inherited its name. Seven of the eight public methods on what is now
 * {@link ai.tessary.classifier.finding.FindingService} serve every classifier; so does the
 * {@code finding} table, whose rows carry a {@code classifier_key}; so does the whole triage queue.
 * Splitting {@code behavior/} by file layout would have meant either publishing behaviour drift's
 * fitted-state representation open, or leaving open code importing a package that had gone paid.
 *
 * <h2>The seam contract</h2>
 *
 * <p>Four rules, and they are what stop the next extraction re-litigating the shape:
 *
 * <ol>
 *   <li><b>Ports live here, in an open package, and adapters are discovered rather than referenced.</b>
 *       A port is injected as a Spring {@code List<T>} — ordered by {@code @Order}, which is the whole
 *       of the routing — or as {@code Optional<T>}. The rule binds the PORTS: no port declares a
 *       classifier's own type in its signature. It does not yet bind the package, and saying it did
 *       would be false — {@code BehaviorTriageSource} lives here and imports ten metric-drift and
 *       tool-error types, because the shared table's own adapter is the one place that still reaches
 *       into the two open classifiers. Those are open and staying open, so nothing is blocked by it;
 *       an adapter for a PAID classifier could never sit here.
 *       {@link ai.tessary.classifier.finding.TriageSource} and
 *       {@link ai.tessary.classifier.finding.CauseResolver} are the two.
 *   <li><b>Ports key on the open {@code BuiltInDetector.Kind} constants</b>, which stay constant.
 *       {@code BuiltInClassifierCatalog.builtIns()} is never filtered by availability:
 *       {@code ClassifierService.retireDroppedBuiltIns} keys on catalog membership and retirement is
 *       IRREVERSIBLE, so a classifier dropping out of the catalog would silently retire a customer's.
 *   <li><b>An absent adapter degrades to what the surface already shows for a classifier with no
 *       data</b> — an empty list, a null block, a 404 — never a wiring failure and never a 500. That is
 *       what lets an edition ship without the adapter at all.
 *   <li><b>Wire records are open; their factories are adapter-side.</b>
 *       {@link ai.tessary.classifier.finding.BehaviorReadiness} stays open even though #919 moved
 *       its only reference, {@code BehaviorDtos.BehaviorProfileView}, into
 *       {@code tessary-paid/behavior-drift} — losing that reference also dropped
 *       {@code BehaviorReadiness} out of the checked-in OpenAPI spec (nothing open reaches it any more),
 *       but it stays open regardless: a 2026-08-30 decision this issue does not re-litigate. Its own
 *       factory, {@code BehaviorProfileViews.readiness(...)}, reads {@code BehaviorProfileRow},
 *       {@code BehaviorDriftConfig} and {@code BehaviorFitCarry}, none of which can stay open, so the
 *       record/factory split still holds for it alone.
 * </ol>
 *
 * <p>The prefixed names — {@code BehaviorDtos}, {@code BehaviorTriage*} — are wire lineage, not package
 * membership. The paths, controller names, method names and record simple names are pinned by
 * {@code OpenApiSpecDriftTest}; renaming them is a deliberate wire decision, not a side effect of
 * moving a package. {@code BehaviorController} itself made exactly that deliberate move in #921,
 * once #919 left the {@code /behavior/profiles} route as the only thing still named {@code Behavior}
 * on the open side: it is {@link ai.tessary.classifier.finding.FindingController} now, at
 * {@code /findings/*} — {@code BehaviorDtos} and {@code BehaviorTriage*} were not renamed with it.
 */
package ai.tessary.classifier.finding;

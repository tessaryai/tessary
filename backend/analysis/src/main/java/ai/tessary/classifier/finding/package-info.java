// SPDX-License-Identifier: Apache-2.0
/**
 * The shared findings and triage feature. Ports live here and adapters are discovered ({@code List<T>} or
 * {@code ObjectProvider<T>}): {@link ai.tessary.classifier.finding.TriageSource} keys on the open
 * {@code BuiltInDetector.Kind} constants, and {@link ai.tessary.classifier.finding.FindingAdapter} on a predicate over
 * the claim. The catalog is never filtered by availability, since {@code ClassifierService.retireDroppedBuiltIns}
 * irreversibly retires dropped keys. An absent adapter degrades to empty, never a 500. The {@code Behavior*} names are
 * wire lineage pinned by {@code OpenApiSpecDriftTest}.
 */
package ai.tessary.classifier.finding;

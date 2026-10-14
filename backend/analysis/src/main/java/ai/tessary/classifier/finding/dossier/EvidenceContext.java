// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;

/**
 * What an owning adapter's RCA {@code evidence.md} is written from beside the finding itself: the per-role counts on
 * the finding row and the evidence enumeration section {@link ClassifierDossierAssembler#enumeration} renders, so the
 * adapter and the shape assemblers list the same bounded page.
 */
public record EvidenceContext(EvidenceCounts counts, String enumeration) {}

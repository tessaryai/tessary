// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;

/**
 * Inputs for an adapter's RCA {@code evidence.md}, so it lists the same bounded page as
 * {@link ClassifierDossierAssembler#enumeration}.
 */
public record EvidenceContext(EvidenceCounts counts, String enumeration) {}

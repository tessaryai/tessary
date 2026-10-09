// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;
import java.util.List;
import java.util.Locale;

/**
 * Cross-cutting rather than classifier-specific: shared by every assembler
 * in this package, deciding whether the finding's evidence table is small enough to enumerate whole or
 * large enough that only a DECLARED selection is shown.
 *
 * <p>The read is bounded to {@link ClassifierDossierAssembler#SMALL_EVIDENCE_SET_CAP}+1 rows either way
 * ({@link FindingEvidenceRepository#claimFirst} over-fetches by one, so the extra row's existence answers "is this the
 * whole population" without a second query). The declared selection IS that bounded read, labelled differently
 * depending on whether it turned out to be everything.
 */
final class EvidenceEnumeration {

    private EvidenceEnumeration() {}

    static String section(
            FindingEvidenceRepository evidence, String projectId, String findingId, EvidenceCounts counts) {
        long total = counts.total();
        StringBuilder sb = new StringBuilder("## Evidence enumeration\n\n");
        if (total == 0) {
            sb.append("No evidence rows recorded for this finding.\n");
            return sb.toString();
        }

        int cap = ClassifierDossierAssembler.SMALL_EVIDENCE_SET_CAP;
        FindingEvidenceRepository.Head head = evidence.claimFirst(projectId, findingId, cap);
        List<FindingEvidenceRow> rows = head.rows();
        boolean complete = total <= cap && !head.more();

        if (complete) {
            sb.append(String.format(
                    Locale.ROOT,
                    "Complete enumeration — every evidence row this finding recorded (%d total), `witness`"
                            + " first, then `baseline`, then the rest:%n%n",
                    rows.size()));
        } else {
            sb.append(String.format(
                    Locale.ROOT,
                    "DECLARED SELECTION, not the full population: the first %d of %d total evidence"
                            + " row(s), `witness` first, then `baseline`, then the rest. Page a role in"
                            + " full with get_finding_evidence. Population by role:%n%n",
                    rows.size(),
                    total));
            for (String role : FindingEvidenceRow.Role.ALL) {
                long c = counts.forRole(role);
                if (c > 0) sb.append("- `").append(role).append("`: ").append(c).append(" ref(s)\n");
            }
            sb.append('\n');
        }
        for (FindingEvidenceRow r : rows) {
            sb.append("- `").append(r.role()).append('`');
            if (r.sessionId() != null)
                sb.append(" session=`").append(r.sessionId()).append('`');
            sb.append(" trace=`")
                    .append(r.traceId() == null ? "-" : r.traceId())
                    .append("` span=`")
                    .append(r.spanId() == null ? "-" : r.spanId())
                    .append("`\n");
        }
        return sb.toString();
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding.dossier;

import ai.tessary.evals.classifier.finding.FindingEvidenceRepository;
import ai.tessary.evals.classifier.finding.FindingEvidenceRow;
import ai.tessary.evals.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;
import java.util.List;
import java.util.Locale;

/**
 * D.2 (#994)'s fourth shape, cross-cutting rather than classifier-specific: shared by every assembler
 * in this package, deciding whether the finding's evidence table is small enough to enumerate whole or
 * large enough that only a DECLARED selection is shown.
 *
 * <p>The read is bounded to {@link ClassifierDossierAssembler#SMALL_EVIDENCE_SET_CAP}+1 rows either
 * way ({@link FindingEvidenceRepository#page} over-fetches by one the same way {@code McpToolRegistry
 * .getTrace} does, for the same reason — the extra row's mere existence answers "is this the whole
 * population" without a second query), so a 200,000-row finding costs the same one bounded read as a
 * 20-row one: the declared selection IS that same bounded page, just labelled differently depending on
 * whether it turned out to be everything.
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
        FindingEvidenceRepository.Page page = evidence.page(projectId, findingId, null, cap, null);
        List<FindingEvidenceRow> rows = page.rows();
        boolean complete = total <= cap && page.nextCursor() == null;

        if (complete) {
            sb.append(String.format(
                    Locale.ROOT,
                    "Complete enumeration — every evidence row this finding recorded (%d total), in the"
                            + " detector's own (role, rank, id) order:%n%n",
                    rows.size()));
        } else {
            sb.append(String.format(
                    Locale.ROOT,
                    "DECLARED SELECTION, not the full population: the first %d of %d total evidence"
                            + " row(s), in the detector's own (role, rank, id) order — the same"
                            + " deterministic order get_finding_evidence pages in, so continuing from"
                            + " here (not re-sampling) reaches the rest. Population by role:%n%n",
                    rows.size(),
                    total));
            for (String role : FindingEvidenceRow.Role.ALL) {
                long c = counts.forRole(role);
                if (c > 0) sb.append("- `").append(role).append("`: ").append(c).append(" ref(s)\n");
            }
            sb.append('\n');
        }
        for (FindingEvidenceRow r : rows) {
            sb.append("- `")
                    .append(r.role())
                    .append("` trace=`")
                    .append(r.traceId() == null ? "-" : r.traceId())
                    .append("` span=`")
                    .append(r.spanId() == null ? "-" : r.spanId())
                    .append("`\n");
        }
        return sb.toString();
    }
}

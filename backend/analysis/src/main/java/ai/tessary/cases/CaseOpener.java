// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Opens or joins a case for a settled finding, inside the caller's transaction. It must run with the ruling, because a
 * ruled finding leaves {@code ux_finding_live} and no later sweep would see it.
 */
@Service
public class CaseOpener {

    private final List<CaseSource> sources;
    private final FindingRepository findings;
    private final CaseLedger ledger;

    public CaseOpener(List<CaseSource> sources, FindingRepository findings, CaseLedger ledger) {
        this.sources = sources;
        this.findings = findings;
        this.ledger = ledger;
    }

    /**
     * Idempotent, so the triage and human paths can both call it. @param actor null for a machine ruling, the acting
     * user's email for a person's
     */
    public Optional<CaseRow> ensureCaseFor(String projectId, String findingId, @Nullable String actor) {
        FindingRow finding = findings.findById(projectId, findingId).orElse(null);
        if (finding == null || !qualifies(finding)) {
            return Optional.empty();
        }
        CaseSource source = sourceFor(finding.classifierKey());
        return Optional.of(ledger.openOrJoin(projectId, source.shape(finding), actor, Instant.now()));
    }

    /** One way in: a ruling of {@link FindingRow.TriageVerdict#POSITIVE}, whoever wrote it. */
    private static boolean qualifies(FindingRow finding) {
        return FindingRow.TriageVerdict.POSITIVE.equals(finding.triageVerdict());
    }

    private CaseSource sourceFor(String classifierKey) {
        return sources.stream().filter(s -> s.owns(classifierKey)).findFirst().orElseThrow();
    }
}

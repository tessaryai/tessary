// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The finding-driven door onto the case table: given a finding that just settled, decide whether it
 * qualifies for a case and, if it does, shape and open or join one — all inside the caller's own
 * transaction, the same one that just wrote the finding's ruling.
 *
 * <p><b>Replaces the periodic reconciler.</b> A case used to open on the next sweep after a finding was
 * ruled; now it opens (or joins) in the same transaction as the ruling itself, because a ruled finding
 * leaves {@code ux_finding_live} for good (decision 1) — there is no later sweep that would ever see it
 * again to open the case it earned.
 *
 * <p>Callers: {@code BehaviorTriageSource.recordVerdict} (a machine's positive) and {@code
 * BehaviorTriageSource.resolve} (a person's <em>Real deviation</em>), both with the finding's ruling
 * already committed in the same transaction; {@code ClassifierArming.evaluateFaceted} (a high-confidence
 * secret-leak facet, which it rules positive itself, without triage, just before calling here).
 */
@Service
public class CaseOpener {

    private static final Logger log = LoggerFactory.getLogger(CaseOpener.class);

    private final List<CaseSource> sources;
    private final FindingRepository findings;
    private final CaseLedger ledger;

    public CaseOpener(List<CaseSource> sources, FindingRepository findings, CaseLedger ledger) {
        this.sources = sources;
        this.findings = findings;
        this.ledger = ledger;
    }

    /**
     * Open or join the case for {@code findingId}, or do nothing when it does not currently qualify.
     * Idempotent: calling this again for a finding that already has its case is a cheap no-op join (see
     * {@link CaseLedger#openOrJoin}), so both the triage path and the human path can call it without
     * either having to know whether the other already did.
     *
     * @param actor null for a machine ruling, the acting user's email for a person's
     */
    public Optional<CaseRow> ensureCaseFor(String projectId, String findingId, @Nullable String actor) {
        FindingRow finding = findings.findById(projectId, findingId).orElse(null);
        if (finding == null || !qualifies(finding)) {
            return Optional.empty();
        }
        CaseSource source = sourceFor(finding.classifierKey());
        if (source == null) {
            log.warn("no case source owns classifier={} for finding={}", finding.classifierKey(), findingId);
            return Optional.empty();
        }
        return Optional.of(ledger.openOrJoin(projectId, source.shape(finding), actor, Instant.now()));
    }

    /**
     * Whether this finding, as it stands right now, is a fact a case exists for.
     *
     * <p>One way in, read straight off the row: a ruling that landed {@link FindingRow.TriageVerdict#POSITIVE},
     * whether triage, a person, or {@code ClassifierArming} for a high-confidence leak wrote it.
     */
    private static boolean qualifies(FindingRow finding) {
        return FindingRow.TriageVerdict.POSITIVE.equals(finding.triageVerdict());
    }

    private @Nullable CaseSource sourceFor(String classifierKey) {
        for (CaseSource source : sources) {
            if (source.owns(classifierKey)) return source;
        }
        return null;
    }
}

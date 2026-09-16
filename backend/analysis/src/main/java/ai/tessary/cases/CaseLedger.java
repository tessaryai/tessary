// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case table's write side: given one finding whose ruling just qualified it for a case, make the
 * ledger agree. Called from {@link CaseOpener}, itself called from inside the same transaction as the
 * ruling write — a case that exists with no "opened" or "recurred" line in its trail is a case a reader
 * cannot account for, so every state change and its activity-trail entry commit together.
 *
 * <p><b>Event-driven, not swept.</b> There is no longer a live set to reconcile: a ruling freezes the
 * finding it landed on (decision 1), so nothing here ever needs to ask "is this detection still firing"
 * — the finding either joined a case when it was ruled, or it did not, and it stays exactly as ruled
 * from then on. A case closes only when a person resolves or absorbs it.
 */
@Service
public class CaseLedger {

    /**
     * How much worse a live case has to get before the trail records it. Without a floor, every finding
     * that joins a slowly-bleeding case would append a line, and the trail — whose job is to still read
     * clearly months later — would drown in its own heartbeat.
     */
    private static final double ESCALATION_STEP = 0.1;

    private final CaseRepository cases;
    private final CaseEventRepository events;
    /** Links the joining finding to the case ({@link FindingRepository#attachToCase}) and closes a
     *  case's findings when it resolves or is absorbed ({@link #closeFindings}). */
    private final FindingRepository findings;

    public CaseLedger(CaseRepository cases, CaseEventRepository events, FindingRepository findings) {
        this.cases = cases;
        this.events = events;
        this.findings = findings;
    }

    /**
     * Open a case for {@code detection}, or join it onto the live, unlocked case already open for its
     * key. Either way the case comes back with {@code detection}'s finding linked
     * ({@code finding.case_id}).
     *
     * <ul>
     *   <li><b>No live case on the key</b>: a new case opens, with an {@code opened} event.
     *   <li><b>A live, unlocked case exists</b>: the finding is linked to it, a {@code recurred} event is
     *       appended (skipped on a re-apply that finds the finding already linked — see
     *       {@link #join}), the headline (title, basis, the value triple) refreshes to this finding's
     *       own account, severity only ever rises to the new peak, and an {@code escalated} event marks
     *       a material one. The onset never moves: the case's story is still the same spell.
     *   <li><b>A live case is locked</b> (1c: RCA has been pressed on it): {@link CaseRepository#findLive}
     *       does not see it, so this opens a fresh case instead of joining one an agent may already be
     *       mid-analysis on.
     * </ul>
     */
    @Transactional
    public CaseRow openOrJoin(String projectId, CaseDetection detection, @Nullable String actor, Instant now) {
        Optional<CaseRow> live = cases.findLive(projectId, detection.key());
        if (live.isPresent()) {
            return join(projectId, live.get(), detection, actor, now);
        }

        Optional<CaseRow> opened = cases.open(projectId, detection, now);
        if (opened.isPresent()) {
            events.append(
                    projectId,
                    opened.get().id(),
                    CaseEventRow.Kind.OPENED,
                    actor,
                    "Opened — " + detection.basis(),
                    null,
                    now);
            return opened.get();
        }
        // Lost the race to a concurrent opener on the same key; the winner's case is the case, so this
        // is a join rather than a second open.
        CaseRow winner = cases.findLive(projectId, detection.key())
                .orElseThrow(() -> new IllegalStateException("case open declined with no live case on key detector="
                        + detection.key().detector() + " subject="
                        + detection.key().subjectId() + " metric="
                        + detection.key().metric()));
        return join(projectId, winner, detection, actor, now);
    }

    /**
     * Close one case as <em>absorbed</em>: a human ruled the shift legitimate and the detector's reference
     * has already been moved to include it, so the level is the new baseline rather than a regression
     * anyone will be asked about again.
     *
     * <p>Here rather than in {@link CaseService} for this class's stated reason: the state change and its
     * trail entry commit together, and a {@code @Transactional} method invoked through {@code this} gets
     * neither a proxy nor a transaction. The re-pin itself is the finding's, and has already committed by
     * the time this is called — deliberately in that order, since a case closed against a bar that never
     * moved would look wrong the moment it reopened.
     */
    @Transactional
    public void absorb(String projectId, String caseId, @Nullable String actor, Instant now) {
        String reason = "Legitimate — absorbed into the detector's reference, so this level is the new baseline.";
        cases.resolve(projectId, caseId, CaseRow.Resolution.ABSORBED, reason, actor, now);
        closeFindings(projectId, caseId, now);
        events.append(projectId, caseId, CaseEventRow.Kind.ABSORBED, actor, reason, null, now);
    }

    /** Close every open finding this case holds — the finding half of resolving or absorbing a case,
     *  in the same transaction as the case's own close. */
    private void closeFindings(String projectId, String caseId, Instant now) {
        findings.closeByCase(projectId, caseId, now.toString());
    }

    private CaseRow join(String projectId, CaseRow row, CaseDetection detection, @Nullable String actor, Instant now) {
        boolean linked = findings.attachToCase(projectId, detection.findingId(), row.id(), now.toString());
        if (!linked && !alreadyLinkedHere(projectId, detection.findingId(), row.id())) {
            // The finding is claimed by a different case already. Never true for a ruling this is called
            // from — a finding that just qualified for a case has case_id NULL by construction — but a
            // defensive no-op rather than an overwrite if it ever happens: this case is not the finding's
            // case, so its headline must not be rewritten from a detection that is not really about it.
            return row;
        }

        double severity = Math.max(row.severity(), detection.severity());
        boolean escalated = detection.severity() - row.severity() >= ESCALATION_STEP;
        cases.refresh(projectId, row.id(), withSeverity(detection, severity), now);

        // recurred fires only on an actual join, never on a re-apply of a finding already linked here —
        // ClassifierArming re-evaluates a still-firing facet on every sweep, and a trail line per sweep
        // would drown the one that mattered.
        if (linked) {
            events.append(
                    projectId,
                    row.id(),
                    CaseEventRow.Kind.RECURRED,
                    actor,
                    "Fired again — " + detection.basis(),
                    null,
                    now);
        }
        if (escalated) {
            events.append(
                    projectId,
                    row.id(),
                    CaseEventRow.Kind.ESCALATED,
                    actor,
                    "Got worse — " + detection.basis(),
                    null,
                    now);
        }
        return cases.findById(projectId, row.id()).orElse(row);
    }

    private boolean alreadyLinkedHere(String projectId, String findingId, String caseId) {
        return findings.findById(projectId, findingId)
                .map(FindingRow::caseId)
                .map(caseId::equals)
                .orElse(false);
    }

    /** {@code detection} with its severity replaced by the case's running peak — everything else (the
     *  title, basis and value triple) still comes from the newest finding, unchanged. Always rebuilt
     *  rather than short-circuited on equality: {@code severity} is the {@code Math.max} of two doubles,
     *  and comparing floating-point values for equality is the kind of "optimization" that quietly
     *  breaks on a value that is merely very close. */
    private static CaseDetection withSeverity(CaseDetection detection, double severity) {
        return new CaseDetection(
                detection.key(),
                detection.subjectLabel(),
                detection.callSiteId(),
                detection.findingId(),
                detection.title(),
                detection.basis(),
                severity,
                detection.onsetAt(),
                detection.currentValue(),
                detection.baselineValue(),
                detection.delta());
    }
}

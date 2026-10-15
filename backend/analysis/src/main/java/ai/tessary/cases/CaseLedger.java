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
 * Called inside the ruling's transaction, so every state change commits with its trail entry. Event-driven: a ruling
 * freezes its finding, so nothing here asks whether a detection is still firing.
 */
@Service
public class CaseLedger {

    /** Without a floor, every finding joining a slowly worsening case would append a trail line. */
    private static final double ESCALATION_STEP = 0.1;

    private final CaseRepository cases;
    private final CaseEventRepository events;
    private final FindingRepository findings;

    public CaseLedger(CaseRepository cases, CaseEventRepository events, FindingRepository findings) {
        this.cases = cases;
        this.events = events;
        this.findings = findings;
    }

    /**
     * Severity only rises; the onset never moves. A locked case is invisible to {@link CaseRepository#findLive}, so
     * this opens a fresh case rather than joining one an agent may be analysing.
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
        // Lost the race to a concurrent opener; join the winner.
        CaseRow winner = cases.findLive(projectId, detection.key())
                .orElseThrow(() -> new IllegalStateException("case open declined with no live case on key detector="
                        + detection.key().detector() + " subject="
                        + detection.key().subjectId() + " metric="
                        + detection.key().metric()));
        return join(projectId, winner, detection, actor, now);
    }

    /**
     * Here rather than in {@link CaseService} because a {@code @Transactional} method called through {@code this} gets
     * no transaction. The re-pin has already committed by the time this runs.
     */
    @Transactional
    public void absorb(String projectId, String caseId, @Nullable String actor, Instant now) {
        String reason = "Legitimate — absorbed into the detector's reference, so this level is the new baseline.";
        cases.resolve(projectId, caseId, CaseRow.Resolution.ABSORBED, reason, actor, now);
        closeFindings(projectId, caseId, now);
        events.append(projectId, caseId, CaseEventRow.Kind.ABSORBED, actor, reason, null, now);
    }

    private void closeFindings(String projectId, String caseId, Instant now) {
        findings.closeByCase(projectId, caseId, now.toString());
    }

    private CaseRow join(String projectId, CaseRow row, CaseDetection detection, @Nullable String actor, Instant now) {
        boolean linked = findings.attachToCase(projectId, detection.findingId(), row.id(), now.toString());
        if (!linked && !alreadyLinkedHere(projectId, detection.findingId(), row.id())) {
            // Defensive no-op rather than overwriting the headline of a case that is not the finding's.
            return row;
        }

        double severity = Math.max(row.severity(), detection.severity());
        boolean escalated = detection.severity() - row.severity() >= ESCALATION_STEP;
        cases.refresh(projectId, row.id(), withSeverity(detection, severity), now);

        // Only on an actual join: ClassifierArming re-evaluates a firing facet every sweep.
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

    /** Always rebuilt rather than short-circuited on floating-point equality. */
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

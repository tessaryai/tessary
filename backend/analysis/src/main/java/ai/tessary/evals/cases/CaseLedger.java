// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case table's write side: given what a detector is firing right now, make the ledger agree.
 * Separate from {@link CaseReconciler} because this half is transactional and that half is not —
 * detection can be seconds of replay, and a Spring proxy cannot apply {@code @Transactional} to a
 * self-invoked method anyway.
 *
 * <p>Every state change and its activity-trail entry commit together. A case that exists with no
 * "opened" line in its trail is a case a reader cannot account for.
 */
@Service
public class CaseLedger {

    private static final Logger log = LoggerFactory.getLogger(CaseLedger.class);

    /**
     * A detection re-firing inside this window continues the case it already had, rather than opening
     * a second one. The point is continuity for the human: a flapping grader that opened, recovered
     * and broke again on the same afternoon is one story, and its trail should read that way.
     */
    private static final Duration REOPEN_WINDOW = Duration.ofDays(7);

    /**
     * How much worse a live case has to get before the trail records it. Without a floor, every pass
     * over a slowly-bleeding grader would append a line, and the trail — whose job is to still read
     * clearly months later — would drown in its own heartbeat.
     */
    private static final double ESCALATION_STEP = 0.1;

    private final CaseRepository cases;
    private final CaseEventRepository events;

    public CaseLedger(CaseRepository cases, CaseEventRepository events) {
        this.cases = cases;
        this.events = events;
    }

    /**
     * Reconcile one detector's live set. {@code firing} is everything the detector currently believes,
     * not a delta — cases whose detection has dropped out of it are closed as recovered.
     */
    @Transactional
    public void apply(String projectId, String detector, List<CaseDetection> firing, Instant now) {
        // One reconcile per project at a time. Case numbers are allocated as MAX(seq)+1, which two
        // backends sweeping the same project would read identically before either commits — the loser's
        // insert then collides on ux_eval_case_seq and its detection is dropped. Every other worker in
        // this codebase avoids that by claiming its work FOR UPDATE SKIP LOCKED; CaseWorker sweeps every
        // project instead, so the exclusion has to happen here. Released when the transaction ends.
        cases.lockProject(projectId);

        Map<CaseKey, CaseDetection> byKey = new LinkedHashMap<>();
        for (CaseDetection d : firing) byKey.put(d.key(), d);

        for (CaseDetection detection : byKey.values()) {
            openOrRefresh(projectId, detection, now);
        }

        for (CaseRow live : cases.listLiveByDetector(projectId, detector)) {
            if (byKey.containsKey(keyOf(live))) continue;
            cases.resolve(projectId, live.id(), CaseRow.Resolution.RECOVERED, null, null, now);
            events.append(
                    projectId,
                    live.id(),
                    CaseEventRow.Kind.RECOVERED,
                    null,
                    "Recovered on its own — the detection stopped firing.",
                    null,
                    now);
        }
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
     * moved would reopen on the next pass.
     */
    @Transactional
    public void absorb(String projectId, String caseId, @Nullable String actor, Instant now) {
        String reason = "Legitimate — absorbed into the detector's reference, so this level is the new baseline.";
        cases.resolve(projectId, caseId, CaseRow.Resolution.ABSORBED, reason, actor, now);
        events.append(projectId, caseId, CaseEventRow.Kind.ABSORBED, actor, reason, null, now);
    }

    private void openOrRefresh(String projectId, CaseDetection detection, Instant now) {
        Optional<CaseRow> live = cases.findLive(projectId, detection.key());
        if (live.isPresent()) {
            refresh(projectId, live.get(), detection, now);
            return;
        }

        Optional<CaseRow> recent = cases.findResolvedSince(projectId, detection.key(), now.minus(REOPEN_WINDOW));
        if (recent.isPresent()) {
            CaseRow row = recent.get();
            // Only a NEW spell reopens. A detection whose onset is the one the human already closed
            // is the same degradation still running, not a re-fire — reopening it would undo their
            // decision on the next tick and make "resolve" look broken. Closing a still-firing
            // detection is a legitimate act ("known, we shipped the fix, the window hasn't caught up");
            // if it is genuinely still wrong, the next distinct spell opens a fresh case.
            if (!isNewSpell(detection, row)) return;
            cases.reopen(projectId, row.id(), detection, now);
            events.append(
                    projectId,
                    row.id(),
                    CaseEventRow.Kind.REOPENED,
                    null,
                    "Fired again — " + detection.basis(),
                    null,
                    now);
            return;
        }

        Optional<CaseRow> opened = cases.open(projectId, detection, now);
        if (opened.isPresent()) {
            events.append(
                    projectId,
                    opened.get().id(),
                    CaseEventRow.Kind.OPENED,
                    null,
                    "Opened — " + detection.basis(),
                    null,
                    now);
            return;
        }
        // Lost the race to a concurrent pass; the winner's case is the case, so this is an update.
        Optional<CaseRow> winner = cases.findLive(projectId, detection.key());
        if (winner.isPresent()) {
            refresh(projectId, winner.get(), detection, now);
            return;
        }
        // open() declined and yet nothing live exists on the key. With an explicit conflict target that
        // should be unreachable; if it happens, a detection was dropped and nobody would otherwise know.
        log.warn(
                "case open declined with no live case on key detector={} subject={} metric={}",
                detection.key().detector(),
                detection.key().subjectId(),
                detection.key().metric());
    }

    private void refresh(String projectId, CaseRow row, CaseDetection detection, Instant now) {
        cases.refresh(projectId, row.id(), detection, now);
        if (detection.severity() - row.severity() >= ESCALATION_STEP) {
            events.append(
                    projectId,
                    row.id(),
                    CaseEventRow.Kind.ESCALATED,
                    null,
                    "Got worse — " + detection.basis(),
                    null,
                    now);
        }
    }

    /**
     * A detection is a new spell when its onset moved past the one the resolved case recorded.
     *
     * <p><b>That comparison is a recovery test, not a clock reading.</b> Every detector's onset is frozen
     * for as long as its detection keeps firing and advances only once the detection has dropped out and
     * come back — see {@code FindingRepository.recordRecomputedCause}. So "the onset moved" means
     * "we watched this recover and break again", which is the question actually being asked here, and the
     * reason a detection that has simply never stopped cannot reopen a case a human closed.
     *
     * <p>Both uncertain answers are "no". A detector that could not bracket the spell hands us a null
     * onset, and a null onset can never have moved — treating it as new would advance it on every pass
     * and reopen the case within one tick of a human closing it, which is the whole failure this guard
     * exists to prevent. An unreadable stored onset is the same argument: a case that reopens forever
     * is worse than one that stays shut, and the next genuinely distinct spell opens a fresh case
     * either way once the reopen window lapses.
     */
    private static boolean isNewSpell(CaseDetection detection, CaseRow resolved) {
        Instant onset = detection.onsetAt();
        if (onset == null) return false;
        try {
            return onset.isAfter(Instant.parse(resolved.onsetAt()));
        } catch (DateTimeParseException e) {
            log.warn("unreadable stored onset on case={} — not reopening", resolved.reference());
            return false;
        }
    }

    private static CaseKey keyOf(CaseRow row) {
        return new CaseKey(row.detector(), row.subjectKind(), row.subjectId(), row.metric());
    }
}

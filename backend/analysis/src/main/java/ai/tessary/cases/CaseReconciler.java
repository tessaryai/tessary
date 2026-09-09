// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.open.obs.LogContext;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Asks every {@link CaseSource} what is firing and hands the answer to {@link CaseLedger}.
 *
 * <p><b>Why reconcile rather than react to events.</b> The detectors are not event streams. CUSUM
 * recomputes a spell from 28 days of history on every evaluation, and a spell that ends leaves
 * nothing behind to fire an "ended" event from. Restating the full live set each pass is the only
 * formulation where a recovery closes its own case with nobody being told — the brief's auto-resolve.
 *
 * <p>It is also what keeps Triage cheap. The replay runs here, on a worker, once per cadence; the
 * app's first screen reads a table.
 */
@Service
public class CaseReconciler {

    private static final Logger log = LoggerFactory.getLogger(CaseReconciler.class);

    private final List<CaseSource> sources;
    private final CaseLedger ledger;

    public CaseReconciler(List<CaseSource> sources, CaseLedger ledger) {
        this.sources = sources;
        this.ledger = ledger;
    }

    /** Reconcile every detector for one project. A failing source is logged and skipped — one broken
     *  detector must not keep the others out of Triage. */
    public void reconcile(String projectId) {
        try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, projectId)) {
            for (CaseSource source : sources) {
                try {
                    // A withheld source is SKIPPED, not asked. Asking and applying its answer would
                    // be wrong however it answered: the live-set contract means an empty list is the
                    // claim "everything I opened has recovered", so a detector that has merely
                    // stopped looking (shadow mode, a withdrawn capability) would auto-resolve cases
                    // a human is mid-triage on and report the project healthy. Skipping leaves those
                    // cases untouched and opens no new ones. Do not "simplify" this into a source
                    // that returns an empty list — that is the bug, not the tidy version of it.
                    if (source.withheldFor(projectId)) {
                        log.debug("case reconcile withheld detector={}", source.detector());
                        continue;
                    }
                    List<CaseDetection> firing = source.detect(projectId);
                    ledger.apply(projectId, source.detector(), firing, Instant.now());
                } catch (RuntimeException e) {
                    log.error("case reconcile failed detector={}", source.detector(), e);
                }
            }
        }
    }
}

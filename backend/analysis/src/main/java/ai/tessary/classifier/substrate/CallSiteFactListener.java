// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.open.obs.Markers;
import ai.tessary.pipeline.CallSiteFactChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reopens swept history to the built-ins gated on a call-site fact when that fact lands or changes. Synthesis only runs
 * after traffic is ingested, so that traffic is always swept before the fact exists, and without this it would read as
 * clean forever. AFTER_COMMIT with {@code fallbackExecution=true}, because publishers call both inside and outside
 * transactions; failures are logged, since the next synthesis publishes again.
 */
@Component
public class CallSiteFactListener {

    private static final Logger log = LoggerFactory.getLogger(CallSiteFactListener.class);

    private final ClassifierService signals;

    public CallSiteFactListener(ClassifierService signals) {
        this.signals = signals;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallSiteFactChanged(CallSiteFactChangedEvent event) {
        try {
            signals.rewindForCallSiteFact(event.projectId(), event.fact(), event.callSiteIds());
        } catch (RuntimeException e) {
            log.warn(
                    Markers.OPS,
                    "signal sweep rewind failed project={} fact={} callSites={}",
                    event.projectId(),
                    event.fact(),
                    event.callSiteIds(),
                    e);
        }
    }
}

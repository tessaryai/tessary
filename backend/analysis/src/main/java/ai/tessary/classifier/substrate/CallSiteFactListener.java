// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.ClassifierSeedListener;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.open.obs.Markers;
import ai.tessary.pipeline.CallSiteFactChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-opens already-swept history to the built-ins that gate on a call-site fact, whenever that fact
 * lands or changes.
 *
 * <p>The ordering this exists for is structural, not a race: agentic synthesis is what captures
 * {@code call_site.output_schema} and {@code call_site.shape} from the repo, and the plugin will not
 * assess a repo until the platform has ingested correctly-tagged traces from it. So the traffic that
 * unblocks synthesis is necessarily traffic Malformed Output and Groundedness could not score — they
 * abstain with no fact, and {@link ClassifierWorker}'s cursor advances over the abstention regardless.
 * Without this listener that history is unscoreable forever, and the product renders "never checked"
 * as "clean".
 *
 * <p>Direction matters: {@code pipeline/} publishes and knows nothing about classifiers, exactly as
 * {@code tenant/} does for {@link ClassifierSeedListener}. Auto-classification reads TRACES, not the
 * repo, and must not acquire a dependency on the slice that owns the repo's shadow.
 *
 * <p>AFTER_COMMIT with {@code fallbackExecution=true}: the rewind must not run against a fact that
 * later rolls back, and the publishers ({@code PipelineService}) call from outside a transaction as
 * well as inside one. Failures are logged and swallowed — the fact is already persisted, and the next
 * synthesis run over the same call site publishes again.
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

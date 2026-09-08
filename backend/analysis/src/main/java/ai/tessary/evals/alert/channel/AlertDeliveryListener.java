// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import ai.tessary.evals.alert.AlertFiredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans a fired alert out to every enabled channel for its project. Uses the
 * AFTER_COMMIT listener idiom:
 * it listens on {@link AlertFiredEvent}, which the worker publishes only when a NEW {@code alert_event} row
 * committed, so a window re-evaluated across N backends fires once → delivers once.
 *
 * <p>AFTER_COMMIT (not a plain {@code @EventListener}): the worker persists the row in a transaction; firing
 * before commit could let this listener read no row. {@code fallbackExecution=true} keeps it working
 * with no surrounding transaction (e.g. a test publishing the event directly).
 *
 * <p>This listener only moves the hand-off to the post-commit
 * boundary — it does NOT run the blocking work inline. The actual fan-out (N sequential blocking HTTP
 * POSTs, each up to the 30s {@link ChannelHttp} timeout) is delegated to
 * {@link AlertDeliveryDispatcher#deliverAll} on the {@code @Async("alertDeliveryExecutor")} pool, so a
 * slow or hanging upstream can never stall the single {@code @Scheduled} AlertWorker thread that
 * publishes the event (the AlertWorker fires events inline with {@code fallbackExecution=true} and no
 * surrounding transaction, so the listener would otherwise run in the worker's own thread).
 */
@Component
public class AlertDeliveryListener {

    private final AlertDeliveryDispatcher dispatcher;

    public AlertDeliveryListener(AlertDeliveryDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertFired(AlertFiredEvent event) {
        // Hand off to the @Async dispatcher (separate bean so the proxy applies); never block the
        // publisher thread on the blocking HTTP fan-out.
        dispatcher.deliverAll(event.row());
    }
}

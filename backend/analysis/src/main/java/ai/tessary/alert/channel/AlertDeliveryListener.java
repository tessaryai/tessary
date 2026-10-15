// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertFiredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans a fired alert out to every enabled channel. {@link AlertFiredEvent} is published only for a newly committed
 * {@code alert_event}, so N backends deliver once. AFTER_COMMIT so the row is visible; {@code fallbackExecution=true}
 * because AlertWorker publishes outside a transaction. The blocking HTTP fan-out runs on
 * {@link AlertDeliveryDispatcher#deliverAll}'s async pool so a hung upstream cannot stall AlertWorker's thread.
 */
@Component
public class AlertDeliveryListener {

    private final AlertDeliveryDispatcher dispatcher;

    public AlertDeliveryListener(AlertDeliveryDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertFired(AlertFiredEvent event) {
        // Separate bean so the @Async proxy applies.
        dispatcher.deliverAll(event.row());
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.llm.ProviderCredentialSavedEvent;
import ai.tessary.open.obs.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Lifts a provider pause when the org saves the key it was waiting on ({@link
 * ClassifierService#unpauseForProvider}). Failures are logged and swallowed: the key is stored either
 * way, and the paused sweep re-checks it on its own after the credential retry interval.
 */
@Component
public class ProviderCredentialListener {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialListener.class);

    private final ClassifierService classifiers;

    public ProviderCredentialListener(ClassifierService classifiers) {
        this.classifiers = classifiers;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProviderCredentialSaved(ProviderCredentialSavedEvent event) {
        try {
            classifiers.unpauseForProvider(event.orgId(), event.provider());
        } catch (RuntimeException e) {
            log.warn(Markers.OPS, "classifier unpause failed org={} provider={}", event.orgId(), event.provider(), e);
        }
    }
}

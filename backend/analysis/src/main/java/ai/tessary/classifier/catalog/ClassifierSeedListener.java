// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.ProjectCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Seeds the built-in classifier catalog into every new project, independent of any repo or grader set. Fires once;
 * later changes (catalog bumps, capability flips) are {@link ClassifierCatalogWorker}'s job via
 * {@link ClassifierService#resyncBuiltIns}. AFTER_COMMIT because the seeded rows reference the project row. Failures
 * are logged, since the heartbeat re-seeds.
 */
@Component
public class ClassifierSeedListener {

    private static final Logger log = LoggerFactory.getLogger(ClassifierSeedListener.class);

    private final ClassifierService signals;

    public ClassifierSeedListener(ClassifierService signals) {
        this.signals = signals;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProjectCreated(ProjectCreatedEvent event) {
        try {
            signals.seedBuiltIns(event.projectId());
        } catch (RuntimeException e) {
            log.warn(
                    Markers.OPS,
                    "signal catalog seed-on-create failed project={} (heartbeat will retry)",
                    event.projectId(),
                    e);
        }
    }
}

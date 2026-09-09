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
 * Seeds the built-in classifier catalog into every new project.
 *
 * <p>Auto-classification reads TRACES, not the repo and not the grader set — so it must not depend on
 * either. Seeding used to hang off the first successful generation run ({@code SynthJobProcessor}),
 * which made classification unreachable for any project without a connected GitHub App: generation is
 * hard-gated on a repo, nothing else called {@code seedBuiltIns}, and {@code ClassifierController}
 * exposes no create endpoint. That also took {@code Runs} down with it, since a {@code grader_run} is
 * only ever enqueued by a classifier detection.
 *
 * <p>This fires ONCE and reflects the org's capabilities at that instant. Everything after — a project
 * that predates this listener, a catalog version bump, a capability flag flipped months later — is
 * {@link ClassifierCatalogWorker}, which calls {@link ClassifierService#resyncBuiltIns} for every active
 * project on a heartbeat. The periodic pass is deliberately not conditioned on the project having any
 * traces: it used to be, and a flag turned on for a quiet project then did nothing until that project's
 * first span arrived.
 *
 * <p>AFTER_COMMIT (not a plain {@code @EventListener}): {@code seedBuiltIns} writes {@code classifier}
 * rows referencing the project, so it must not run before the project row commits. Failures are
 * logged and swallowed — the project is already created, and the heartbeat re-seeds.
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

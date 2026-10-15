// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.ProjectCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gives every new project an enabled case-opened alert rule. Nothing is delivered until a channel exists, so a partner
 * who adds one gets cases with no rule to compose. Like {@code ClassifierSeedListener}, AFTER_COMMIT and failures
 * logged, since {@link AlertService#ensureCaseOpenedRule} is idempotent.
 */
@Component
public class CaseAlertSeedListener {

    private static final Logger log = LoggerFactory.getLogger(CaseAlertSeedListener.class);

    private final AlertService alerts;

    public CaseAlertSeedListener(AlertService alerts) {
        this.alerts = alerts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProjectCreated(ProjectCreatedEvent event) {
        try {
            alerts.ensureCaseOpenedRule(event.projectId());
        } catch (RuntimeException e) {
            log.warn(Markers.OPS, "case-opened alert rule seed failed project={}", event.projectId(), e);
        }
    }
}

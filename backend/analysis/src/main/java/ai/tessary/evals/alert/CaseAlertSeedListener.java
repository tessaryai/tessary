// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.tenant.ProjectCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gives every new project a case-opened alert rule, enabled — launch requirement I4, "alerting is on for
 * partners by default".
 *
 * <p><b>Why this is a real default and not a token one.</b> Nothing is delivered until the project has a
 * channel, so seeding the rule notifies nobody on its own. What it does is remove the second step: a
 * partner who pastes a Slack webhook, or installs the app, starts getting cases immediately, with no
 * settings page to discover and no rule to compose. The alternative — seed nothing, and let them find the
 * notification page — is how a product ships with alerting that most partners never turn on and we
 * conclude nobody wanted it.
 *
 * <p>Modelled on {@code ClassifierSeedListener}, deliberately including its failure posture: AFTER_COMMIT
 * so the project row exists before anything references it, and a swallowed-and-logged failure, because
 * the project is already created and {@link AlertService#ensureCaseOpenedRule} is idempotent enough for
 * any later call to fix it.
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

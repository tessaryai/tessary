// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import ai.tessary.config.IntelligenceProperties;
import ai.tessary.tenant.Ids;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the intelligence-mode evidence row once per backend boot — the durable SOC 2
 * Type II evidence artifact recording whether cross-customer pooling was reachable on this
 * deployment. Runs on {@link ApplicationReadyEvent} (after Liquibase has applied the schema, so
 * the table exists) rather than in a hot-path service's {@code @PostConstruct}, keeping the priors
 * service free of any boot-time DB coupling.
 *
 * <p>Failure to write the evidence row must never block boot — it is logged and swallowed; the
 * authoritative runtime gate is the config flag enforced in {@link PriorsService}, and the boot log
 * line is the secondary evidence. The audit table is the convenient export, not the enforcement.
 */
@Component
public class IntelligenceModeAuditor {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceModeAuditor.class);

    private final IntelligenceProperties intelligence;
    private final PriorsGovernance governance;
    private final IntelligenceModeAuditRepository repo;

    public IntelligenceModeAuditor(
            IntelligenceProperties intelligence, PriorsGovernance governance, IntelligenceModeAuditRepository repo) {
        this.intelligence = intelligence;
        this.governance = governance;
        this.repo = repo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recordBootPosture() {
        boolean singleTenant = intelligence.isSingleTenant();
        boolean poolingEnabled = governance.poolingEnabled();
        try {
            repo.record(Ids.ulid(), singleTenant, poolingEnabled, Instant.now().toString());
            log.info(
                    "intelligence-mode: recorded boot evidence (single-tenant={}, pooling-enabled={})",
                    singleTenant,
                    poolingEnabled);
        } catch (RuntimeException e) {
            // Evidence write is best-effort; the config flag is the authoritative enforcement.
            log.warn("intelligence-mode: failed to record boot evidence row (continuing)", e);
        }
    }
}

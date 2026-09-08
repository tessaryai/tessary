// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment-level intelligence-mode governance, bound from {@code evals.intelligence-mode.*}.
 * This is the single, top-level, auditable kill-switch for cross-customer data use —
 * the SOC 2 evidence control an operator points an auditor at to prove the deployment is
 * single-tenant.
 *
 * <p><b>Single-tenant intelligence is the default enterprise shape</b>: a
 * deployment delivers per-customer value (its own priors, its own synthesis/optimizer signal) with
 * <em>provably zero cross-customer pooling</em>. When {@link #singleTenant} is {@code true} (the
 * default), the priors pipeline hard-refuses to pool, contribute, or derive across tenants
 * <em>regardless</em> of {@code evals.priors.enabled} — so a misconfigured or accidentally-enabled
 * {@code evals.priors.*} can never cause cross-tenant pooling on a single-tenant deployment. The
 * flag wins over every per-org consent row.
 *
 * <p><b>Why a second flag on top of {@code evals.priors.enabled}.</b> {@code evals.priors.enabled}
 * is the <em>feature</em> switch for the governed pooling pipeline (k-anonymity + DP-ε). This is the
 * <em>tenancy-mode</em> switch: a single, coarse, fail-closed boundary that an auditor can read
 * without reasoning about the priors governance internals. Defence in depth — two independent gates,
 * the outer one defaulting to the safe (single-tenant) state.
 *
 * <p>Mirrors {@link PriorsProperties}/{@link SynthProperties} in shape (default-safe,
 * knob-per-governance-lever, validated-at-boot via {@code @ConfigurationPropertiesScan}).
 */
@Component
@ConfigurationProperties(prefix = "evals.intelligence-mode")
public class IntelligenceProperties {

    /**
     * Tenancy mode for intelligence. {@code true} (default) → <b>single-tenant</b>: no tenant's
     * data is ever pooled or read across the cross-customer boundary, whatever {@code evals.priors.*}
     * or per-org consent say. {@code false} → the governed cross-customer pooling pipeline is allowed
     * to run (still gated, separately, by {@code evals.priors.enabled} + per-org consent +
     * k-anonymity + DP-ε). Defaults to the safe, enterprise-default state so a deployment is
     * single-tenant unless an operator deliberately opts the whole deployment into pooling.
     */
    private boolean singleTenant = true;

    public boolean isSingleTenant() {
        return singleTenant;
    }

    public void setSingleTenant(boolean v) {
        this.singleTenant = v;
    }
}

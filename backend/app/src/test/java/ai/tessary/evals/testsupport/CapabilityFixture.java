// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.testsupport;

import ai.tessary.evals.featureflags.DbFeatureFlags;
import ai.tessary.evals.featureflags.OrgFeatureFlagRepository;
import ai.tessary.evals.plan.Capability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pins a capability ON (or OFF) for one org, for tests whose subject sits behind one.
 *
 * <p><b>Why this exists.</b> The suite used to run with {@code evals.plan.default-key=enterprise} in
 * {@code app/pom.xml} surefire, so every capability was ambiently on and no test had to say which one it
 * needed. Open-core epic 1 issue 1 removed plan tiers and that property with them, and the open-edition
 * default is every capability ON <em>except</em> {@code behavior_drift}, {@code sop_conformance}, {@code
 * frustration} and {@code groundedness} (the four paid classifiers — #887/#888 added the last two
 * 2026-08-31) and {@code triage_automatic} (opt-in). A test exercising any of those five is now testing
 * the default rather than its own subject unless it grants the capability first — which is what the pom's
 * own replacement note says a test must do, stated once here instead of ten times.
 *
 * <p>Writing a real {@code org_feature_flag} row rather than stubbing {@code FeatureFlags} keeps the
 * resolution path under test end to end: the row, {@code DbFeatureFlags}, and {@code CapabilityService}'s
 * override-beats-default rule are all exercised exactly as they are in production.
 *
 * <p>{@link DbFeatureFlags} caches per org for ten seconds, so every write invalidates — without that a grant
 * made inside a test would not be visible to the code under test in the same test.
 *
 * <p>That cache is held through an {@link ObjectProvider} because this is a scanned {@code @Component} and so
 * gets constructed in EVERY {@code @SpringBootTest} context in the module, including the several that replace
 * {@code FeatureFlags} with a {@code @MockitoBean}. Those contexts have no {@code DbFeatureFlags} at all —
 * {@code FeatureFlagsConfig} only creates one when no other {@code FeatureFlags} bean exists — so a required
 * dependency here would fail their context load over a fixture they never call.
 */
@Component
public class CapabilityFixture {

    private final OrgFeatureFlagRepository overrides;
    private final ObjectProvider<DbFeatureFlags> flags;

    @Autowired
    public CapabilityFixture(OrgFeatureFlagRepository overrides, ObjectProvider<DbFeatureFlags> flags) {
        this.overrides = overrides;
        this.flags = flags;
    }

    /** Pin one capability ON for one org. */
    public void grant(String orgId, Capability capability) {
        set(orgId, capability, true);
    }

    /** Pin one capability OFF for one org — an explicit row, not the absence of one. */
    public void withhold(String orgId, Capability capability) {
        set(orgId, capability, false);
    }

    /** Drop the org's opinion, returning the capability to the edition default. */
    public void clear(String orgId, Capability capability) {
        overrides.delete(orgId, capability.wire());
        invalidate(orgId);
    }

    private void set(String orgId, Capability capability, boolean enabled) {
        overrides.upsert(orgId, capability.wire(), enabled);
        invalidate(orgId);
    }

    private void invalidate(String orgId) {
        DbFeatureFlags open = flags.getIfAvailable();
        if (open != null) {
            open.invalidate(orgId);
        }
    }
}

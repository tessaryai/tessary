// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FeatureFlags} over {@code org_feature_flag} — the OPEN edition's flag adapter, and the one the
 * build ships with. LaunchDarkly is the hosted adapter behind the same interface; the resolution layer above
 * cannot tell them apart, which is the whole point of the seam.
 *
 * <p>It holds no defaults, exactly like the LaunchDarkly adapter it replaces. A capability with no row for
 * this org returns empty — "nobody has an opinion" — and {@code CapabilityService} supplies the open-edition
 * default. An empty table therefore serves the same configuration as a table nobody has touched, which is what
 * makes a fresh self-hosted install a coherent product rather than a dark one.
 *
 * <p><b>Why this caches.</b> Resolving one capability payload asks about all 21 capabilities, and the
 * capability interceptor asks again on every gated request. Without the cache that is 21 SELECTs per payload
 * and one per request; with it, one whole-org read per {@link #TTL}. The window is short because the thing it
 * delays is an operator flipping a switch in Settings and expecting the product to change — and
 * {@link #invalidate} makes that immediate on this node anyway, so the TTL only bounds how long a
 * <em>different</em> node serves the old answer. Same 10-second shape as {@code CapabilityIngestQuotaGate}.
 */
public class DbFeatureFlags implements FeatureFlags {

    /** How long one org's override map is reused before it is re-read. */
    static final Duration TTL = Duration.ofSeconds(10);

    private final OrgFeatureFlagRepository overrides;

    /**
     * Keyed by org id. Nothing evicts, so this is unbounded in principle — but it is bounded in practice by
     * the org count, which is one on a self-hosted install and small on ours. No caching library is in use
     * anywhere else in this codebase, so this is a plain map on purpose rather than a Caffeine cache.
     */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public DbFeatureFlags(OrgFeatureFlagRepository overrides) {
        this.overrides = overrides;
    }

    @Override
    public Optional<Boolean> override(String key, FlagContext context) {
        String orgId = context.orgId();
        if (orgId == null || orgId.isBlank()) {
            // FlagContext.global(). There is no global row and deliberately no global table: an override is
            // something an ORGANIZATION states about itself. A global evaluation therefore has no opinion,
            // which lands the caller on the open-edition default — the same answer LaunchDarkly's global
            // scope gave when no rule matched.
            return Optional.empty();
        }
        return Optional.ofNullable(forOrg(orgId).get(key));
    }

    /**
     * Drop one org's cached map so the next read re-loads it. Called by the write path, so an operator's
     * toggle bites on this node immediately instead of up to {@link #TTL} later.
     */
    public void invalidate(String orgId) {
        cache.remove(orgId);
    }

    private Map<String, Boolean> forOrg(String orgId) {
        Instant now = Instant.now();
        Entry entry = cache.get(orgId);
        if (entry == null || !now.isBefore(entry.expiresAt())) {
            entry = new Entry(Map.copyOf(overrides.findByOrg(orgId)), now.plus(TTL));
            cache.put(orgId, entry);
        }
        return entry.flags();
    }

    /** One org's whole override map plus the instant it stops being reused. */
    private record Entry(Map<String, Boolean> flags, Instant expiresAt) {}
}

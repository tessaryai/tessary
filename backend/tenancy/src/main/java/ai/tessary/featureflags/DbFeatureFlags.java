// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FeatureFlags} over {@code org_feature_flag}. Holds no defaults: no row returns empty and
 * {@code CapabilityService} supplies the default, so an empty table is a coherent product. Caches one whole-org read
 * per {@link #TTL}; {@link #invalidate} makes a toggle immediate on this node.
 */
public class DbFeatureFlags implements FeatureFlags {

    static final Duration TTL = Duration.ofSeconds(10);

    private final OrgFeatureFlagRepository overrides;

    /**
     * Unbounded in principle, bounded in practice by the org count. A plain map since no caching library is used
     * elsewhere.
     */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public DbFeatureFlags(OrgFeatureFlagRepository overrides) {
        this.overrides = overrides;
    }

    @Override
    public Optional<Boolean> override(String key, FlagContext context) {
        String orgId = context.orgId();
        if (orgId == null || orgId.isBlank()) {
            // No global row by design: an override is something an organization states about itself.
            return Optional.empty();
        }
        return Optional.ofNullable(forOrg(orgId).get(key));
    }

    /** Called by the write path. */
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

    private record Entry(Map<String, Boolean> flags, Instant expiresAt) {}
}

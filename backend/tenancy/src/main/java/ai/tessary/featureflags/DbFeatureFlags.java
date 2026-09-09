// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FeatureFlags} over {@code org_feature_flag}, this build's flag adapter. A build may wire a
 * different adapter behind the same interface; the resolution layer above cannot tell them apart.
 *
 * <p>It holds no defaults. A capability with no row for this org returns empty, "nobody has an opinion",
 * and {@code CapabilityService} supplies the default. An empty table therefore serves the same
 * configuration as a table nobody has touched, which is what makes a fresh self-hosted install a
 * coherent product rather than a dark one.
 *
 * <p>Resolving one capability payload asks about all 21 capabilities, and the capability interceptor
 * asks again on every gated request, so this caches: one whole-org read per {@link #TTL} rather than a
 * SELECT per request. {@link #invalidate} makes an operator's toggle immediate on this node, so the TTL
 * only bounds how long a different node serves the old answer.
 */
public class DbFeatureFlags implements FeatureFlags {

    /** How long one org's override map is reused before it is re-read. */
    static final Duration TTL = Duration.ofSeconds(10);

    private final OrgFeatureFlagRepository overrides;

    /**
     * Keyed by org id. Nothing evicts, so this is unbounded in principle, but it is bounded in practice by
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
            // something an organization states about itself, so a global evaluation has no opinion and
            // lands the caller on the default.
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

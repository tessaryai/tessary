// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.plan;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.featureflags.DbFeatureFlags;
import ai.tessary.evals.featureflags.OrgFeatureFlagRepository;
import ai.tessary.evals.open.errors.CapabilityError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.tenant.rbac.Permission;
import ai.tessary.evals.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The org's capability surface: the resolved payload every session is assembled from, and the overrides an
 * operator sets to change it.
 *
 * <p>Was {@code PlanController}, which served the capability payload and the plan/quota detail from one class.
 * The plan half is the HOSTED product and moved to the paid overlay's own plan controller with the
 * entitlement engine; the capability half is what the open SPA is built from and stays here, on the same path
 * and with a byte-compatible {@code capabilities} map so no frontend change was needed to split them.
 *
 * <p>{@link #capabilities} is readable by ANY org member, because every member's app is assembled from it. The
 * overrides underneath it are {@link Permission#CAPABILITIES_MANAGE} — owner and admin. The server-side
 * {@code CapabilityService.require} on each gated endpoint remains the authority; this is what the UI is built
 * from, not what enforces it.
 *
 * <p>The Settings → Features UI that drives the override endpoints is deliberately not part of this change; it
 * is issue #880 under epic 7.
 */
@RestController
public class CapabilityController {

    private static final Logger log = LoggerFactory.getLogger(CapabilityController.class);

    private final CapabilityService capabilities;
    private final OrgFeatureFlagRepository overrides;
    private final TenantPathResolver resolver;

    /**
     * Optional on purpose. {@code DbFeatureFlags} caches an org's overrides for ten seconds, and a write should
     * bite immediately rather than after that window — but it is the OPEN adapter, and a build whose
     * {@code FeatureFlags} bean is the hosted LaunchDarkly one has no such bean to invalidate. An
     * {@link ObjectProvider} is how this controller stays correct in both editions instead of failing to start
     * in one of them.
     */
    private final ObjectProvider<DbFeatureFlags> openFlags;

    public CapabilityController(
            CapabilityService capabilities,
            OrgFeatureFlagRepository overrides,
            TenantPathResolver resolver,
            ObjectProvider<DbFeatureFlags> openFlags) {
        this.capabilities = capabilities;
        this.overrides = overrides;
        this.resolver = resolver;
        this.openFlags = openFlags;
    }

    /**
     * The resolved state of EVERY capability for this org, keyed by flag key — the one object the SPA assembles
     * itself from. Every capability is present with an explicit boolean rather than only the enabled ones
     * listed, so the client never has to decide what an absent key means.
     *
     * <p>{@code unavailable} is the second question a client has to be able to answer: a capability can be off
     * because nobody turned it on, or absent because this edition does not carry the code behind it. Those need
     * different UI — a switch versus an explanation — and the difference is not derivable from the map. No
     * upgrade-prompt mechanism is implied; that is epic 10's in-product upgrade path.
     */
    public record CapabilitiesView(
            Map<String, Boolean> capabilities,
            @JsonProperty("unavailable") List<String> unavailable) {}

    /** One capability's override state: what it resolves to, what it would resolve to, and who decided. */
    public record OverrideView(
            String capability,
            boolean enabled,
            @JsonProperty("open_default") boolean openDefault,
            @JsonProperty("has_override") boolean hasOverride,
            boolean unavailable) {}

    /** Pin one capability on or off for this org. */
    public record SetOverrideRequest(boolean enabled) {}

    /** The org's capability object. Readable by any org member. */
    @GetMapping("/api/orgs/{orgSlug}/capabilities")
    public ApiResponse<CapabilitiesView> capabilities(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        var resolved = capabilities.resolve(r.org().id());
        // LinkedHashMap over Capability's declaration order: the payload reads in catalog order rather than
        // hash order, which makes a diff between two orgs legible by eye.
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Capability capability : Capability.all()) {
            out.put(capability.wire(), resolved.isEnabled(capability));
        }
        List<String> unavailable = new ArrayList<>();
        for (Capability capability : Capability.all()) {
            if (capabilities.unavailable().contains(capability)) {
                unavailable.add(capability.wire());
            }
        }
        return ApiResponse.ok(new CapabilitiesView(out, List.copyOf(unavailable)));
    }

    /**
     * Every capability with its override state — the read behind the Features settings screen. Gated at
     * {@link Permission#ORG_VIEW} rather than {@code CAPABILITIES_MANAGE}: seeing why a surface is missing is
     * not a privileged act, and the row says nothing an admin could not tell a member out loud.
     */
    @GetMapping("/api/orgs/{orgSlug}/capabilities/overrides")
    public ApiResponse<List<OverrideView>> overrides(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.ORG_VIEW, "view capability overrides");
        String orgId = r.org().id();
        Map<String, Boolean> stored = overrides.findByOrg(orgId);
        var resolved = capabilities.resolve(orgId);
        List<OverrideView> out = new ArrayList<>();
        for (Capability capability : Capability.all()) {
            out.add(new OverrideView(
                    capability.wire(),
                    resolved.isEnabled(capability),
                    capabilities.defaultFor(capability),
                    stored.containsKey(capability.wire()),
                    capabilities.unavailable().contains(capability)));
        }
        return ApiResponse.ok(out);
    }

    /** Pin one capability on or off for this org. */
    @PutMapping("/api/orgs/{orgSlug}/capabilities/overrides/{key}")
    public ApiResponse<OverrideView> setOverride(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String key,
            @Valid @RequestBody SetOverrideRequest body) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.CAPABILITIES_MANAGE, "change a capability");
        Capability capability = requireAvailable(key);
        requireDbOverrides(capability);
        overrides.upsert(r.org().id(), capability.wire(), body.enabled());
        invalidate(r.org().id());
        log.info("org {} set capability {}={}", r.org().id(), capability.wire(), body.enabled());
        return ApiResponse.ok(one(r.org().id(), capability));
    }

    /** Drop the org's override, reverting the capability to this edition's default. */
    @DeleteMapping("/api/orgs/{orgSlug}/capabilities/overrides/{key}")
    public ApiResponse<OverrideView> clearOverride(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String key) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.CAPABILITIES_MANAGE, "change a capability");
        Capability capability = requireAvailable(key);
        requireDbOverrides(capability);
        overrides.delete(r.org().id(), capability.wire());
        invalidate(r.org().id());
        log.info("org {} cleared the capability override for {}", r.org().id(), capability.wire());
        return ApiResponse.ok(one(r.org().id(), capability));
    }

    /**
     * Resolve a wire key, refusing both an unknown one and one this edition cannot honour. The second refusal
     * is a 422 and NOT a silent no-op on purpose: an operator who "enables" a classifier whose code is absent
     * would get an empty result set and go hunting for the bug in their traces.
     */
    private Capability requireAvailable(String key) {
        Capability capability =
                Capability.fromWire(key).orElseThrow(() -> new EvalsException(CapabilityError.UNKNOWN, key));
        if (capabilities.unavailable().contains(capability)) {
            throw new EvalsException(CapabilityError.UNAVAILABLE, capability.wire());
        }
        return capability;
    }

    /**
     * Per-org overrides are the OPEN adapter's mechanism. In the paid edition {@code FeatureFlags} is the
     * LaunchDarkly adapter and the only flag control there is (epic 5, decision 3), so a row written into
     * {@code org_feature_flag} would be read by nothing; refusing the write is honest, a silent no-op would
     * not be.
     */
    private void requireDbOverrides(Capability capability) {
        if (openFlags.getIfAvailable() == null) {
            throw new EvalsException(CapabilityError.OVERRIDES_MANAGED_EXTERNALLY, capability.wire());
        }
    }

    private void invalidate(String orgId) {
        DbFeatureFlags flags = openFlags.getIfAvailable();
        if (flags != null) {
            flags.invalidate(orgId);
        }
    }

    private OverrideView one(String orgId, Capability capability) {
        Map<String, Boolean> stored = overrides.findByOrg(orgId);
        return new OverrideView(
                capability.wire(),
                capabilities.isEnabled(orgId, capability),
                capabilities.defaultFor(capability),
                stored.containsKey(capability.wire()),
                capabilities.unavailable().contains(capability));
    }
}

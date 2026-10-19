// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
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
 * The org's resolved capabilities and the overrides that change them. {@code CapabilityService.require} on each gated
 * endpoint remains the authority; this is what the UI is built from.
 */
@RestController
public class CapabilityController {

    private static final Logger log = LoggerFactory.getLogger(CapabilityController.class);

    private final CapabilityService capabilities;
    private final OrgFeatureFlagRepository overrides;
    private final TenantPathResolver resolver;

    /**
     * Optional because a context that mocks {@code FeatureFlags} has no {@code DbFeatureFlags} bean, whose ten-second
     * cache a write must invalidate.
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

    /** Every capability present with an explicit boolean, so the client never interprets an absent key. */
    public record CapabilitiesView(Map<String, Boolean> capabilities) {}

    public record OverrideView(
            String capability,
            boolean enabled,
            @JsonProperty("default_enabled") boolean defaultEnabled,
            @JsonProperty("has_override") boolean hasOverride,
            /** False when the deployment's license lists it off: shown locked, and an override is refused. */
            boolean licensed) {}

    public record SetOverrideRequest(boolean enabled) {}

    /** Readable by any org member. */
    @GetMapping("/api/orgs/{orgSlug}/capabilities")
    public ApiResponse<CapabilitiesView> capabilities(TenantContext ctx, @PathVariable String orgSlug) {
        var r = resolver.requireOrg(ctx, orgSlug);
        var resolved = capabilities.resolve(r.org().id());
        // Catalog order, so a diff between two orgs is legible.
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Capability capability : Capability.all()) {
            out.put(capability.wire(), resolved.isEnabled(capability));
        }
        return ApiResponse.ok(new CapabilitiesView(out));
    }

    /** Gated at {@link Permission#ORG_VIEW}: seeing why a surface is missing is not privileged. */
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
                    capabilities.licensed(capability)));
        }
        return ApiResponse.ok(out);
    }

    @PutMapping("/api/orgs/{orgSlug}/capabilities/overrides/{key}")
    public ApiResponse<OverrideView> setOverride(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String key,
            @Valid @RequestBody SetOverrideRequest body) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.CAPABILITIES_MANAGE, "change a capability");
        Capability capability = requireKnown(key);
        if (!capabilities.licensed(capability)) {
            throw new TessaryException(CapabilityError.LICENSED, capability.wire());
        }
        overrides.upsert(r.org().id(), capability.wire(), body.enabled());
        invalidate(r.org().id());
        log.info("org {} set capability {}={}", r.org().id(), capability.wire(), body.enabled());
        return ApiResponse.ok(one(r.org().id(), capability));
    }

    /** Reverts to this build's default. */
    @DeleteMapping("/api/orgs/{orgSlug}/capabilities/overrides/{key}")
    public ApiResponse<OverrideView> clearOverride(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String key) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.CAPABILITIES_MANAGE, "change a capability");
        Capability capability = requireKnown(key);
        overrides.delete(r.org().id(), capability.wire());
        invalidate(r.org().id());
        log.info("org {} cleared the capability override for {}", r.org().id(), capability.wire());
        return ApiResponse.ok(one(r.org().id(), capability));
    }

    /** 422 for an unknown key. */
    private Capability requireKnown(String key) {
        return Capability.fromWire(key).orElseThrow(() -> new TessaryException(CapabilityError.UNKNOWN, key));
    }

    private void invalidate(String orgId) {
        openFlags.ifAvailable(flags -> flags.invalidate(orgId));
    }

    private OverrideView one(String orgId, Capability capability) {
        Map<String, Boolean> stored = overrides.findByOrg(orgId);
        return new OverrideView(
                capability.wire(),
                capabilities.isEnabled(orgId, capability),
                capabilities.defaultFor(capability),
                stored.containsKey(capability.wire()),
                capabilities.licensed(capability));
    }
}

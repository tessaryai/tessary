// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import ai.tessary.evals.alert.AlertChannelDtos.ChannelView;
import ai.tessary.evals.alert.AlertChannelDtos.DeleteResponse;
import ai.tessary.evals.alert.AlertChannelDtos.DeliveryAttemptView;
import ai.tessary.evals.alert.AlertChannelDtos.UpsertChannelRequest;
import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.auth.TenantPathResolver.Resolved;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD + delivery-log read for a project's alert channels. Owner-gated for mutations,
 * mirroring {@code git/GitIntegrationController}. Credentials are write-only: a channel's config is
 * accepted on create/update but never returned (see {@code ChannelView}).
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/alert-channels")
public class AlertChannelController {

    private static final int DEFAULT_DELIVERY_LIMIT = 200;

    private final AlertChannelService service;
    private final TenantPathResolver resolver;
    private final CapabilityService capabilities;

    public AlertChannelController(
            AlertChannelService service, TenantPathResolver resolver, CapabilityService capabilities) {
        this.service = service;
        this.resolver = resolver;
        this.capabilities = capabilities;
    }

    /**
     * Some transports are a capability of their own. Slack is not part of the launch, so an org without
     * {@link Capability#SLACK} cannot point a channel at it — checked here, at the write, rather than only
     * at delivery, so the refusal lands where the person is rather than in a log nobody reads.
     *
     * <p>An unrecognized kind falls through to {@link AlertChannelService}, which owns that error.
     */
    private void requireChannelKind(Resolved r, String kind) {
        if (AlertChannelKind.SLACK.wire().equalsIgnoreCase(kind == null ? "" : kind.trim())) {
            capabilities.require(r.org().id(), Capability.SLACK);
        }
    }

    @GetMapping
    public ApiResponse<List<ChannelView>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        Resolved r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                service.list(r.project().id()).stream().map(ChannelView::from).toList());
    }

    @PostMapping
    public ApiResponse<ChannelView> create(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody UpsertChannelRequest req) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        requireChannelKind(r, req.kind());
        return ApiResponse.ok(ChannelView.from(service.create(r.project().id(), req)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChannelView> update(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody UpsertChannelRequest req) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        requireChannelKind(r, req.kind());
        return ApiResponse.ok(ChannelView.from(service.update(r.project().id(), id, req)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<DeleteResponse> delete(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        Resolved r = requireOwner(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(new DeleteResponse(service.delete(r.project().id(), id)));
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<DeliveryAttemptView>> deliveries(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(required = false) Integer limit) {
        Resolved r = requireCapableProject(ctx, orgSlug, projectSlug);
        int cap = limit == null || limit <= 0 ? DEFAULT_DELIVERY_LIMIT : Math.min(limit, 1000);
        return ApiResponse.ok(service.recentDeliveries(r.project().id(), cap).stream()
                .map(DeliveryAttemptView::from)
                .toList());
    }

    /** Alerts are a paid capability ({@link Feature#ALERTS}): resolve the project AND require the entitlement. */
    private Resolved requireCapableProject(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        capabilities.require(r.org().id(), Capability.ALERTS);
        return r;
    }

    private Resolved requireOwner(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = requireCapableProject(ctx, orgSlug, projectSlug);
        if (!r.isOwner() && !ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner role required to manage alert channels");
        }
        return r;
    }
}

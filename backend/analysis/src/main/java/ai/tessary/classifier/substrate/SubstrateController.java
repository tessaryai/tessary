// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.ClassifierController;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cookie-authed substrate liveness for onboarding, polled until a first live trace lands. The token-scoped
 * {@code /v1/query/count} rejects browser sessions; anything filtered belongs there, not here.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/substrate")
public class SubstrateController {

    private final SubstrateReadRepository substrate;
    private final TenantPathResolver resolver;

    public SubstrateController(SubstrateReadRepository substrate, TenantPathResolver resolver) {
        this.substrate = substrate;
        this.resolver = resolver;
    }

    /**
     * Liveness short-circuits on the first span; the untagged count caps at
     * {@link SubstrateReadRepository#UNTAGGED_CAP}. The connect-gate fields are read only once traffic has landed, so
     * {@code not_connected} costs one {@code EXISTS}.
     */
    @GetMapping("/status")
    public ApiResponse<SubstrateStatusView> status(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        String projectId = r.project().id();
        boolean hasLive = substrate.hasSpans(projectId);
        if (!hasLive) {
            return ApiResponse.ok(new SubstrateStatusView(false, 0, false, 0, 0, null, null));
        }
        return ApiResponse.ok(new SubstrateStatusView(
                true,
                substrate.untaggedSpans(projectId),
                substrate.hasTaggedSpan(projectId),
                substrate.spansReceived(projectId),
                substrate.taggedSpans(projectId),
                substrate.lastSpanAt(projectId),
                substrate.recentServiceName(projectId)));
    }

    /** Formerly {@code untagged_observations}. */
    public record SubstrateStatusView(
            @JsonProperty("has_live") boolean hasLive,
            @JsonProperty("untagged_spans") long untaggedSpans,
            @JsonProperty("has_tagged_span") boolean hasTaggedSpan,
            @JsonProperty("spans_received") long spansReceived,
            @JsonProperty("tagged_spans") long taggedSpans,
            @JsonProperty("last_span_at") @Nullable String lastSpanAt,
            @JsonProperty("service_name") @Nullable String serviceName) {}
}

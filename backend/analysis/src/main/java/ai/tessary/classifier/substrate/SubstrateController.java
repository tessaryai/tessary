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
 * Browser-reachable substrate liveness surface. The guided onboarding flow ({@code Setup.tsx})
 * polls {@link #status} to detect when a new user's <em>first real</em> trace has landed via the
 * OTLP ({@code POST /v1/traces}).
 *
 * <p><b>Why a cookie-auth endpoint exists alongside {@code /v1/query/count}.</b> Live OTLP traffic
 * writes the {@code observation} substrate, not a graded {@code run}, so the run-centric
 * onboarding detector never fires for it. The only existing substrate counter ({@code POST /v1/query/count},
 * {@code QueryController}) is project-token-scoped and rejects the browser cookie session. This endpoint is
 * the narrow, cookie-authed counterpart the SPA can poll: it is an onboarding <em>liveness</em> check, not
 * a general query replacement — anything filtered/aggregated belongs behind the token-scoped query API.
 *
 * <p>Auth + tenant resolution mirror {@link ClassifierController}: {@link TenantPathResolver#requireProject}
 * scopes the read to the path's project; the response uses the shared {@code ApiResponse} envelope and
 * snake_case JSON. The count distinguishes <b>live</b> (substrate present) from the <b>sample</b> fallback
 * (a graded upload run, no substrate) — the provenance the onboarding TTFV event stamps.
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
     * A project's substrate liveness: a {@code hasLive} flag that is true once any live trace has landed,
     * plus the (capped) count of spans that carry no {@code call_site_id} — the "traces are arriving
     * but nothing is tagged for grading" signal the onboarding wizard and the Pipeline instrument-nudge
     * render. The liveness read short-circuits on the first matching span; the untagged count is
     * capped at {@link SubstrateReadRepository#UNTAGGED_CAP} (the UI renders "1000+" beyond it).
     *
     * <p>Five fields added additively for the connect gate: {@code has_tagged_span} is the
     * gate's own redirect signal, and {@code spans_received}/{@code tagged_spans}/{@code last_span_at}/
     * {@code service_name} feed the untagged wait state's four-stat row. All are read only once traffic
     * has landed at all, same as {@code untagged}, so a project at {@code not_connected} pays for one
     * {@code EXISTS} and nothing past it.
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

    /**
     * Substrate liveness for the onboarding flow: a {@code has_live} flag plus the capped
     * {@code untagged_spans} count (spans with no {@code tessary.call_site.id} tag), plus the
     * connect gate's own {@code has_tagged_span} redirect signal and the untagged wait state's stat row.
     *
     * <p>The field was {@code untagged_observations}; v2 calls the row a span everywhere the wire is
     * regenerated in the same change, and this is one of them.
     */
    public record SubstrateStatusView(
            @JsonProperty("has_live") boolean hasLive,
            @JsonProperty("untagged_spans") long untaggedSpans,
            @JsonProperty("has_tagged_span") boolean hasTaggedSpan,
            @JsonProperty("spans_received") long spansReceived,
            @JsonProperty("tagged_spans") long taggedSpans,
            @JsonProperty("last_span_at") @Nullable String lastSpanAt,
            @JsonProperty("service_name") @Nullable String serviceName) {}
}

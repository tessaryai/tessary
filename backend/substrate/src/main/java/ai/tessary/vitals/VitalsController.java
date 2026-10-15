// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.vitals.VitalsDtos.Vitals;
import ai.tessary.web.ApiResponse;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spend, tool-error rate and turn latency for a window against the one before it. Read-only by design: these are shown
 * to the user and never escalate to a grader run or RCA.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/vitals")
public class VitalsController {

    private final VitalsService service;
    private final TenantPathResolver resolver;

    public VitalsController(VitalsService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    /**
     * @param days the baseline is always the equal-length window before @param by {@code call_site} (default) or
     * {@code model}
     */
    @GetMapping
    public ApiResponse<Vitals> vitals(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "by", defaultValue = "call_site") String by) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.compute(r.project().id(), days, dimension(by)));
    }

    /** Falls back to the default rather than 400-ing, since this is a dashboard read. */
    private static VitalsRepository.Dimension dimension(String raw) {
        try {
            return VitalsRepository.Dimension.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VitalsRepository.Dimension.CALL_SITE;
        }
    }
}

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
 * The vitals surface: spend, tool-error rate and turn latency for a window, against the window before
 * it, grouped by a dimension.
 *
 * <p>Read-only by construction. There is no write endpoint here and no job is enqueued anywhere
 * behind it, which is the whole point — these statistics are raised directly to the user and never
 * escalate to a grader run or an RCA.
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
     * @param days window length; the comparison baseline is always the equal-length window before it
     * @param by grouping dimension — {@code call_site} (the default, since consistency is expected per
     *     call site) or {@code model}. Intent joins this enum when a turn can be bound to one.
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

    /** An unknown dimension falls back to the default rather than 400-ing — this is a dashboard read. */
    private static VitalsRepository.Dimension dimension(String raw) {
        try {
            return VitalsRepository.Dimension.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VitalsRepository.Dimension.CALL_SITE;
        }
    }
}

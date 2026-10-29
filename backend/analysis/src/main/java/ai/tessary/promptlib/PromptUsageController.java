// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.plan.Capability;
import ai.tessary.plan.RequiresCapability;
import ai.tessary.promptlib.PromptUsageDtos.PromptCalls;
import ai.tessary.promptlib.PromptUsageDtos.PromptUsage;
import ai.tessary.promptlib.PromptUsageDtos.PromptUsageSummary;
import ai.tessary.promptlib.PromptUsageDtos.VersionUsage;
import ai.tessary.promptlib.PromptUsageRepository.PromptHead;
import ai.tessary.promptlib.PromptUsageRepository.VersionUsageRow;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.traces.TracePageCodec;
import ai.tessary.web.ApiResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How often each prompt, and each version of one prompt, was called over the last {@code days} days. A controller of
 * its own beside {@code PromptController} so the two never share a file; the literal {@code usage} segment wins over
 * {@code {promptId}}.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/prompts")
@RequiresCapability(Capability.PROMPT_MANAGEMENT)
public class PromptUsageController {

    private static final Logger log = LoggerFactory.getLogger(PromptUsageController.class);

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final TenantPathResolver resolver;
    private final PromptUsageRepository usage;
    private final Clock clock;

    @Autowired
    public PromptUsageController(TenantPathResolver resolver, PromptUsageRepository usage) {
        this(resolver, usage, Clock.systemUTC());
    }

    PromptUsageController(TenantPathResolver resolver, PromptUsageRepository usage, Clock clock) {
        this.resolver = resolver;
        this.usage = usage;
        this.clock = clock;
    }

    /** Every prompt name with a call in the window, in name order; the list page joins it to the prompt list. */
    @GetMapping("/usage/summary")
    public ApiResponse<PromptUsageSummary> summary(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(required = false) @Nullable Integer days) {
        String projectId = requireReader(ctx, orgSlug, projectSlug);
        int window = TracePageCodec.clampLimit(days, DEFAULT_DAYS, MAX_DAYS);
        long started = System.nanoTime();
        List<PromptCalls> items = usage.callsByName(projectId, since(window)).entrySet().stream()
                .map(e -> new PromptCalls(e.getKey(), e.getValue()))
                .toList();
        logComputed(projectId, null, window, items.size(), started);
        return ApiResponse.ok(new PromptUsageSummary(window, items));
    }

    /**
     * One row per version string the spans sent, a null version included. {@code unknown_versions} names the strings
     * that match no version of the prompt: a label such as {@code prod}, or a version deleted since.
     */
    @GetMapping("/{promptId}/usage")
    public ApiResponse<PromptUsage> usage(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String promptId,
            @RequestParam(required = false) @Nullable Integer days) {
        String projectId = requireReader(ctx, orgSlug, projectSlug);
        int window = TracePageCodec.clampLimit(days, DEFAULT_DAYS, MAX_DAYS);
        long started = System.nanoTime();
        PromptHead head = usage.head(projectId, promptId)
                .orElseThrow(() -> new TessaryException(PromptsError.NOT_FOUND, promptId));
        List<VersionUsageRow> rows = usage.byVersion(projectId, head.name(), since(window));

        Set<String> known = new HashSet<>();
        head.versions().forEach(v -> known.add(String.valueOf(v)));
        Set<String> unknown = new TreeSet<>();
        for (VersionUsageRow row : rows) {
            String version = row.version();
            if (version != null && !known.contains(version)) {
                unknown.add(version);
            }
        }
        logComputed(projectId, head.name(), window, rows.size(), started);
        return ApiResponse.ok(
                new PromptUsage(window, rows.stream().map(VersionUsage::from).toList(), List.copyOf(unknown)));
    }

    private String requireReader(TenantContext ctx, String orgSlug, String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view prompt usage");
        if (ctx.isMcpToken() && !ctx.keyPermits(KeyScope.QUERY)) {
            throw new TessaryException(PromptsError.WRONG_KEY_SCOPE, "read");
        }
        return r.project().id();
    }

    private Instant since(int days) {
        return clock.instant().minus(Duration.ofDays(days));
    }

    private static void logComputed(
            String projectId, @Nullable String promptName, int days, int rows, long startedNanos) {
        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        StructuredLog.debug(log, "prompt.usage.computed")
                .message(
                        "computed prompt usage for %s over %s day(s) in %sms, %s row(s)",
                        promptName == null ? "every prompt" : promptName, days, durationMs, rows)
                .field("projectId", projectId)
                .field("promptName", promptName)
                .field("days", days)
                .field("rows", rows)
                .field("durationMs", durationMs)
                .log();
    }
}

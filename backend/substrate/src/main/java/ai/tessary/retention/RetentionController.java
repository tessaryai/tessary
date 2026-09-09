// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.ops.RetentionPolicyRepository;
import ai.tessary.ops.RetentionPolicyRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Data retention (#1205): how long this project keeps traces and detections. The
 * install-wide default comes from {@code tessary.retention.*}; a project override is a
 * {@code retention_policy} row, and clearing the override returns the project to the default.
 * {@code 0} means keep forever. The sweeper reads the same {@link RetentionResolver}, so what this
 * page shows is what the hourly pass enforces.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/retention")
public class RetentionController {

    private final RetentionResolver resolver;
    private final RetentionPolicyRepository policies;
    private final TenantPathResolver tenants;

    public RetentionController(
            RetentionResolver resolver, RetentionPolicyRepository policies, TenantPathResolver tenants) {
        this.resolver = resolver;
        this.policies = policies;
        this.tenants = tenants;
    }

    public record RetentionClassView(
            @JsonProperty("data_class") String dataClass,
            @JsonProperty("ttl_days") int ttlDays,
            @JsonProperty("from_policy") boolean fromPolicy,
            @JsonProperty("platform_default_days") int platformDefaultDays) {}

    public record RetentionView(
            List<RetentionClassView> classes,
            @JsonProperty("can_manage") boolean canManage) {}

    /**
     * Replaces both overrides at once: a number sets that class's override ({@code 0} keeps
     * forever), and {@code null} or an absent key clears it so the install default applies.
     */
    public record RetentionUpdateRequest(
            @Nullable @Min(0) Integer traces,
            @Nullable @Min(0) Integer detections) {}

    @GetMapping
    public ApiResponse<RetentionView> get(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = tenants.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "read data retention");
        return ApiResponse.ok(view(r.project().id(), r.can(Permission.RETENTION_MANAGE)));
    }

    @PutMapping
    public ApiResponse<RetentionView> update(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody RetentionUpdateRequest req) {
        var r = tenants.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.RETENTION_MANAGE, "change data retention");
        apply(r.project().id(), RetentionResolver.DataClass.TRACES, req.traces());
        apply(r.project().id(), RetentionResolver.DataClass.DETECTIONS, req.detections());
        return ApiResponse.ok(view(r.project().id(), true));
    }

    private void apply(String projectId, RetentionResolver.DataClass dataClass, @Nullable Integer ttlDays) {
        if (ttlDays == null) {
            policies.delete(projectId, dataClass.wire());
            return;
        }
        policies.upsert(new RetentionPolicyRow(
                Ids.ulid(),
                projectId,
                dataClass.wire(),
                ttlDays,
                null,
                Instant.now().toString(),
                "{}"));
    }

    private RetentionView view(String projectId, boolean canManage) {
        List<RetentionClassView> classes = resolver.resolve(projectId).stream()
                .map(e -> new RetentionClassView(
                        e.dataClass().wire(), e.ttlDays(), e.fromPolicy(), resolver.platformDefault(e.dataClass())))
                .toList();
        return new RetentionView(classes, canManage);
    }
}

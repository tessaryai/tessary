// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.plan;

import ai.tessary.evals.tenant.OrganizationRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Enforces {@link RequiresCapability} on the way in, so the API cannot serve a capability the SPA has
 * already hidden.
 *
 * <p><b>Resolution, and what it deliberately does not do.</b> The org comes from the {@code orgSlug} path
 * variable, looked up by slug. When there is no such variable, or no such org, this does <em>nothing</em>
 * and lets the request through to the handler — whose own {@code TenantPathResolver} call produces the
 * correct 404/403. Answering here would mean this interceptor inventing tenancy errors in a codebase where
 * exactly one class is allowed to, and getting a 403 where a 404 belongs tells an outsider that an org
 * exists.
 *
 * <p>It runs before the handler's membership check, so a member of another org probing a gated path sees
 * {@code CAPABILITY_DISABLED} rather than {@code forbidden}. Both are 403 and neither reveals anything the
 * capability payload does not already state for the requester's own org.
 */
@Component
public class CapabilityInterceptor implements HandlerInterceptor {

    private final CapabilityService capabilities;
    private final OrganizationRepository orgs;

    public CapabilityInterceptor(CapabilityService capabilities, OrganizationRepository orgs) {
        this.capabilities = capabilities;
        this.orgs = orgs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RequiresCapability required = required(method);
        if (required == null) return true;
        String orgSlug = orgSlug(request);
        if (orgSlug == null) return true;
        orgs.findBySlug(orgSlug).ifPresent(org -> capabilities.require(org.id(), required.value()));
        return true;
    }

    /** Method-level wins over class-level, so one handler can be gated differently from its controller. */
    private static @Nullable RequiresCapability required(HandlerMethod method) {
        RequiresCapability onMethod =
                AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresCapability.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresCapability.class);
    }

    private static @Nullable String orgSlug(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) return null;
        Object slug = map.get("orgSlug");
        return slug instanceof String s && !s.isBlank() ? s : null;
    }
}

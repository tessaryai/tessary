// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.tenant.OrganizationRepository;
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
 * Enforces {@link RequiresCapability}. The org comes from the {@code orgSlug} path variable; with no variable or no
 * such org this lets the request through, so the handler's {@code TenantPathResolver} produces the right 404/403 and an
 * outsider can't learn an org exists. A non-member probing a gated path sees {@code CAPABILITY_DISABLED}; both are 403.
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

    /** Method-level wins over class-level. */
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

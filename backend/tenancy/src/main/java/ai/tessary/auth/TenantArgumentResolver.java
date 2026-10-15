// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Throws 401 when the filter set no {@link TenantContext}, which means a request slipped past the auth filter: a server
 * bug.
 */
@Component
public class TenantArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TenantContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        HttpServletRequest req = webRequest.getNativeRequest(HttpServletRequest.class);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no http request bound");
        }
        TenantContext ctx = (TenantContext) req.getAttribute(TenantContext.ATTRIBUTE);
        if (ctx == null || !ctx.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant context");
        }
        return ctx;
    }
}

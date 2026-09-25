// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * A controller parameter typed {@link TenantContext} is only ever an authenticated caller: the
 * resolver refuses to hand a handler a missing or anonymous context rather than let it run.
 */
class TenantArgumentResolverTest {

    private final TenantArgumentResolver resolver = new TenantArgumentResolver();
    private final MethodParameter param = mock(MethodParameter.class);

    private Object resolve(NativeWebRequest web) {
        return resolver.resolveArgument(param, null, web, null);
    }

    private static ServletWebRequest withContext(@Nullable TenantContext ctx) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orgs/acme");
        if (ctx != null) req.setAttribute(TenantContext.ATTRIBUTE, ctx);
        return new ServletWebRequest(req);
    }

    @Test
    void anAuthenticatedContextIsHandedToTheHandler() {
        TenantContext ctx = new TenantContext("usr_1", "ada@example.com", null, null, null, null);
        assertSame(ctx, resolve(withContext(ctx)));
    }

    @Test
    @SuppressWarnings("NullAway") // deliberate: a context with no user id is the unauthenticated shape
    void aMissingOrAnonymousContextIs401() {
        for (TenantContext ctx : new TenantContext[] {null, new TenantContext(null, null, null, null, null, null)}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> resolve(withContext(ctx)));
            assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        }
    }

    @Test
    void aRequestThatIsNotHttpIs500NotAnAnonymousCaller() {
        NativeWebRequest web = mock(NativeWebRequest.class);
        when(web.getNativeRequest(HttpServletRequest.class)).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> resolve(web));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }
}

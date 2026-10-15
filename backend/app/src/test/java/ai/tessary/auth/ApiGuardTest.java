// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import ai.tessary.testsupport.AuthEnforcedContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every operation the app maps, read from its request mappings at runtime, refuses a caller with no credential (401),
 * and every org-scoped one refuses an org the caller is not in (403), an org that does not exist (404) and a project
 * that does not exist in the caller's own org (404), with no data in the refusal. The bug: a new endpoint that skips
 * {@link TenantPathResolver} or sits on a path {@link AuthFilter} leaves open.
 */
@AuthEnforcedContext
record ApiGuardTest(
        @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings, MockMvc mvc) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link AuthFilter#shouldNotFilter}'s bypass list, plus the {@code /auth/**} routes that serve a visitor. */
    private static final Set<String> PUBLIC = Set.of(
            "/auth/login",
            "/auth/callback",
            "/auth/logout",
            "/auth/link/start",
            "/auth/link/poll",
            "/auth/mode",
            "/auth/signup",
            "/git/github/callback",
            "/git/github/manifest/callback",
            "/v3/api-docs",
            "/v3/api-docs.yaml",
            "/error");

    private record Operation(
            RequestMethod method,
            String pattern,
            HandlerMethod handler,
            @Nullable MediaType consumes) {}

    @Test
    void everyOperationRefusesAnonymousForeignAndUnknownTenants() throws Exception {
        Cookie me = signUp("guard-me@example.com");
        Cookie them = signUp("guard-them@example.com");
        String myOrg = orgSlug(me);
        String theirOrg = orgSlug(them);
        List<String> wrong = new ArrayList<>();
        int orgScoped = 0;
        for (Operation op : operations()) {
            if (PUBLIC.contains(op.pattern())) continue;
            expect(wrong, op, 401, fill(op, theirOrg, "x"), null);
            if (!op.pattern().contains("{orgSlug}") || !guardRunsFirst(op.handler())) continue;
            orgScoped++;
            expect(wrong, op, 403, fill(op, theirOrg, "x"), me);
            expect(wrong, op, 404, fill(op, "no-such-org", "x"), me);
            if (op.pattern().contains("{projectSlug}")) expect(wrong, op, 404, fill(op, myOrg, "no-such-project"), me);
        }
        assertEquals(List.of(), wrong);
        assertTrue(orgScoped > 50, "the mappings were read: " + orgScoped + " org-scoped operations");
    }

    private void expect(List<String> wrong, Operation op, int status, String path, @Nullable Cookie session)
            throws Exception {
        var req = request(HttpMethod.valueOf(op.method().name()), path)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(op.consumes() == null ? MediaType.APPLICATION_JSON : op.consumes())
                .content("{}");
        if (session != null) req.cookie(session);
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        String body = res.getContentAsString();
        JsonNode data =
                body.isEmpty() ? MAPPER.missingNode() : MAPPER.readTree(body).path("data");
        if (res.getStatus() != status || !(data.isMissingNode() || data.isNull())) {
            wrong.add(op.method() + " " + path + ": " + res.getStatus() + " " + body);
        }
    }

    private List<Operation> operations() {
        List<Operation> ops = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            MediaType consumes = info.getConsumesCondition().getConsumableMediaTypes().stream()
                    .findFirst()
                    .orElse(null);
            for (String pattern :
                    Objects.requireNonNull(info.getPathPatternsCondition()).getPatternValues()) {
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    ops.add(new Operation(m, pattern, e.getValue(), consumes));
                }
            }
        }
        return ops;
    }

    /**
     * False when Spring refuses the request while binding it, before the handler can resolve the tenant: a validated
     * body, a required parameter, a part, or a path variable that is not free text.
     */
    private static boolean guardRunsFirst(HandlerMethod handler) {
        for (MethodParameter p : handler.getMethodParameters()) {
            RequestParam param = p.getParameterAnnotation(RequestParam.class);
            boolean validatedBody = p.hasParameterAnnotation(RequestBody.class)
                    && (p.hasParameterAnnotation(Valid.class) || p.hasParameterAnnotation(Validated.class));
            boolean requiredParam =
                    param != null && param.required() && ValueConstants.DEFAULT_NONE.equals(param.defaultValue());
            boolean typedPath = p.hasParameterAnnotation(PathVariable.class) && p.getParameterType() != String.class;
            if (validatedBody || requiredParam || typedPath || p.hasParameterAnnotation(RequestPart.class)) {
                return false;
            }
        }
        return true;
    }

    private static String fill(Operation op, String org, String project) {
        return op.pattern()
                .replace("{orgSlug}", org)
                .replace("{projectSlug}", project)
                .replaceAll("\\{[^}]+}", "x");
    }

    private Cookie signUp(String email) throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", "a-good-password"))))
                .andReturn()
                .getResponse();
        return Objects.requireNonNull(res.getCookie("tessary-session"));
    }

    private String orgSlug(Cookie session) throws Exception {
        String me = mvc.perform(get("/auth/me").cookie(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(me)
                .path("data")
                .path("orgs")
                .path(0)
                .path("slug")
                .asText();
    }
}

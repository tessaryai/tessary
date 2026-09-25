// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The {@link RequiresCapability} gate on the way into a handler, against a real {@link CapabilityService}
 * over an in-memory override store. The bugs it guards: serving a capability an org has switched off,
 * reading the wrong annotation when a method and its controller disagree, and answering for an org the
 * request did not name (which would make this class, not the handler's resolver, the one deciding 404
 * versus 403).
 */
@ExtendWith(MockitoExtension.class)
class CapabilityInterceptorTest {

    private static final String ORG_ID = "org_1";
    private static final String SLUG = "acme";

    @Mock
    private OrganizationRepository orgs;

    private final Map<String, Boolean> overrides = new HashMap<>();
    private final FeatureFlags flags = (key, ctx) -> Optional.ofNullable(overrides.get(key));
    private final CapabilityService capabilities = new CapabilityService(flags);
    private CapabilityInterceptor interceptor;

    /** A controller gated on RCA, with one handler gated on Slack instead. */
    @RequiresCapability(Capability.RCA)
    static final class GatedController {
        public void report() {}

        @RequiresCapability(Capability.SLACK)
        public void post() {}
    }

    static final class OpenController {
        public void list() {}
    }

    @BeforeEach
    void setUp() {
        interceptor = new CapabilityInterceptor(capabilities, orgs);
    }

    private static HandlerMethod handler(Object bean, String name) throws NoSuchMethodException {
        Method m = bean.getClass().getMethod(name);
        return new HandlerMethod(bean, m);
    }

    private static MockHttpServletRequest request(@Nullable Object uriVars) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (uriVars != null) req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriVars);
        return req;
    }

    private void orgExists() {
        when(orgs.findBySlug(SLUG))
                .thenReturn(Optional.of(new Organization(ORG_ID, null, SLUG, "Acme", "t0", null, null)));
    }

    private boolean preHandle(Object handler, @Nullable Object uriVars) {
        return interceptor.preHandle(request(uriVars), new MockHttpServletResponse(), handler);
    }

    @Test
    void aClassLevelGateRefusesAnOrgThatSwitchedTheCapabilityOff() throws Exception {
        orgExists();
        overrides.put(Capability.RCA.wire(), false);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> preHandle(handler(new GatedController(), "report"), Map.of("orgSlug", SLUG)));

        assertEquals(CapabilityError.DISABLED, e.error());
        assertEquals(CapabilityError.DISABLED.render(Capability.RCA.wire()), e.getMessage());
    }

    @Test
    void aMethodLevelGateWinsOverItsControllers() throws Exception {
        orgExists();
        overrides.put(Capability.RCA.wire(), false);

        assertTrue(
                preHandle(handler(new GatedController(), "post"), Map.of("orgSlug", SLUG)),
                "the handler asks for Slack, which is on; the controller's RCA gate does not apply to it");

        overrides.put(Capability.SLACK.wire(), false);
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> preHandle(handler(new GatedController(), "post"), Map.of("orgSlug", SLUG)));
        assertEquals(CapabilityError.DISABLED.render(Capability.SLACK.wire()), e.getMessage());
    }

    @Test
    void anUnknownOrgIsLeftToTheHandlersOwnResolver() throws Exception {
        when(orgs.findBySlug("nobody")).thenReturn(Optional.empty());
        overrides.put(Capability.RCA.wire(), false);

        assertTrue(preHandle(handler(new GatedController(), "report"), Map.of("orgSlug", "nobody")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "no-slug", "blank-slug", "non-string-slug", "not-a-map"})
    void aRequestThatNamesNoOrgPassesThrough(String shape) throws Exception {
        overrides.put(Capability.RCA.wire(), false);
        Object vars =
                switch (shape) {
                    case "none" -> null;
                    case "no-slug" -> Map.of("projectSlug", "web");
                    case "blank-slug" -> Map.of("orgSlug", " ");
                    case "non-string-slug" -> Map.of("orgSlug", 42);
                    default -> "orgSlug=acme";
                };

        assertTrue(preHandle(handler(new GatedController(), "report"), vars), shape);
        verifyNoInteractions(orgs);
    }

    @Test
    void anUngatedHandlerOrANonHandlerMethodPassesThrough() throws Exception {
        overrides.put(Capability.RCA.wire(), false);

        assertTrue(preHandle(handler(new OpenController(), "list"), Map.of("orgSlug", SLUG)));
        assertTrue(preHandle(new Object(), Map.of("orgSlug", SLUG)), "a static resource has no annotation to read");
        verifyNoInteractions(orgs);
    }

    @Test
    void anEnabledCapabilityLetsTheRequestThrough() throws Exception {
        orgExists();

        assertTrue(preHandle(handler(new GatedController(), "report"), Map.of("orgSlug", SLUG)));
    }
}

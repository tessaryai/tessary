// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.AuthProperties;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.link.DeviceLinkController.ConfirmRequest;
import ai.tessary.auth.link.DeviceLinkController.PollRequest;
import ai.tessary.auth.link.DeviceLinkController.PollResponse;
import ai.tessary.auth.link.DeviceLinkController.StartRequest;
import ai.tessary.auth.link.DeviceLinkController.StartResponse;
import ai.tessary.auth.link.DeviceLinkService.PollOutcome;
import ai.tessary.auth.link.DeviceLinkService.Started;
import ai.tessary.auth.link.DeviceLinkService.View;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * The device-link HTTP surface: the plugin-facing start/poll answers (verification URIs, the
 * poll status to HTTP status mapping, the per-IP limiter) and the browser-side confirm gate.
 */
@ExtendWith(MockitoExtension.class)
class DeviceLinkControllerTest {

    /** Ten seconds into a minute, so a burst of calls cannot straddle a window boundary by accident. */
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:10Z");

    @Mock
    DeviceLinkService service;

    @Mock
    TenantPathResolver resolver;

    private final MutableClock clock = new MutableClock(NOW);
    private final TenantContext alice = new TenantContext("usr_alice", "alice@example.com", null, null, null, null);

    private DeviceLinkController controller() {
        AuthProperties props = new AuthProperties();
        props.setFrontendUrl("https://app.example.com/");
        return new DeviceLinkController(service, resolver, props, clock);
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/link/poll");
        req.setRemoteAddr(ip);
        return req;
    }

    @Test
    @SuppressWarnings("NullAway") // deliberate: a start with no body passes a null request, as Spring does
    void start_answersTheVerificationUrisAgainstTheFrontendWithoutADoubledSlash() {
        when(service.start("laptop")).thenReturn(new Started("link_secret", "ABCD-EFGH", NOW.plusSeconds(600)));
        when(service.start(null)).thenReturn(new Started("link_secret", "ABCD-EFGH", NOW.minusSeconds(5)));
        DeviceLinkController c = controller();

        assertEquals(
                new StartResponse(
                        "link_secret",
                        "ABCD-EFGH",
                        "https://app.example.com/link",
                        "https://app.example.com/link?code=ABCD-EFGH",
                        DeviceLinkService.POLL_INTERVAL_SECONDS,
                        600),
                c.start(new StartRequest("laptop"), from("10.0.0.1")).data());
        // No body at all is a legal start (label absent), and an already-past expiry never goes negative.
        assertEquals(
                0,
                Objects.requireNonNull(c.start(null, from("10.0.0.1")).data()).expires_in());
    }

    @ParameterizedTest
    @CsvSource({"expired,GONE", "already_claimed,CONFLICT", "authorization_pending,OK", "slow_down,OK", "denied,OK"})
    void poll_mapsTheOutcomeToTheHttpStatusThePluginBranchesOn(String outcome, HttpStatus expected) {
        when(service.poll("link_x")).thenReturn(PollOutcome.of(outcome));

        ResponseEntity<?> res = controller().poll(new PollRequest("link_x"), from("10.0.0.2"));

        assertEquals(expected, res.getStatusCode());
    }

    @Test
    void poll_readyCarriesTheTokenAndSlugs() {
        when(service.poll("link_x")).thenReturn(new PollOutcome("ready", "tsk_plain", "acme", "web"));

        var res = controller().poll(new PollRequest("link_x"), from("10.0.0.3"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(
                new PollResponse("ready", "tsk_plain", "acme", "web"),
                Objects.requireNonNull(res.getBody()).data());
    }

    @Test
    void rateLimit_capsEachAddressPerMinuteIndependentlyAndResetsNextWindow() {
        when(service.poll("link_x")).thenReturn(PollOutcome.of("authorization_pending"));
        DeviceLinkController c = controller();
        for (int i = 0; i < 60; i++) {
            c.poll(new PollRequest("link_x"), from("10.1.1.1"));
        }

        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> c.poll(new PollRequest("link_x"), from("10.1.1.1")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        assertEquals(
                HttpStatus.OK,
                c.poll(new PollRequest("link_x"), from("10.1.1.2")).getStatusCode(),
                "another address keeps its own allowance");

        clock.advanceSeconds(60);
        assertEquals(
                HttpStatus.OK,
                c.poll(new PollRequest("link_x"), from("10.1.1.1")).getStatusCode(),
                "the next minute's window starts fresh");
    }

    @Test
    void rateLimit_dropsEveryWindowOnceTrackedAddressesPassTheCap() {
        when(service.poll("link_x")).thenReturn(PollOutcome.of("authorization_pending"));
        DeviceLinkController c = controller();
        for (int i = 0; i < 60; i++) {
            c.poll(new PollRequest("link_x"), from("10.2.2.2"));
        }
        assertThrows(ResponseStatusException.class, () -> c.poll(new PollRequest("link_x"), from("10.2.2.2")));
        // 50,000 more addresses put the map over its cap; the next call clears it wholesale, so
        // the exhausted address is admitted again inside the same minute.
        for (int i = 0; i < 50_000; i++) {
            c.poll(new PollRequest("link_x"), from("addr-" + i));
        }

        assertEquals(
                HttpStatus.OK,
                c.poll(new PollRequest("link_x"), from("10.2.2.2")).getStatusCode());
    }

    @Test
    void view_unknownCodeIsNotFound() {
        View v = new View("ABCD-EFGH", "laptop", "pending", NOW.toString());
        when(service.view("ABCD-EFGH")).thenReturn(Optional.of(v));
        when(service.view("NOPE-NOPE")).thenReturn(Optional.empty());

        assertEquals(v, controller().view("ABCD-EFGH").data());
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> controller().view("NOPE-NOPE"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    @SuppressWarnings("NullAway") // deliberate: a JSON body can omit either slug, which binds as null
    void confirm_requiresBothSlugs() {
        for (ConfirmRequest req :
                new ConfirmRequest[] {new ConfirmRequest(null, "web"), new ConfirmRequest("acme", null)}) {
            ResponseStatusException e = assertThrows(
                    ResponseStatusException.class, () -> controller().confirm(alice, "ABCD-EFGH", req));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
    }

    @Test
    void confirm_aRoleThatCannotMintKeysIsRefusedBeforeTheLinkIsBound() {
        when(resolver.requireProject(alice, "acme", "web")).thenReturn(resolved("viewer"));

        ResponseStatusException e = assertThrows(
                ResponseStatusException.class,
                () -> controller().confirm(alice, "ABCD-EFGH", new ConfirmRequest("acme", "web")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(service, never()).confirm(any(), any(), any(), any());
    }

    @Test
    void confirm_bindsTheLinkToTheResolvedProjectAndCaller_orConflictsWhenNoLongerPending() {
        when(resolver.requireProject(alice, "acme", "web")).thenReturn(resolved("member"));
        when(service.confirm("ABCD-EFGH", "org_1", "prj_1", "usr_alice"))
                .thenReturn(true)
                .thenReturn(false);

        assertEquals(
                Map.of("status", "confirmed"),
                controller()
                        .confirm(alice, "ABCD-EFGH", new ConfirmRequest("acme", "web"))
                        .data());
        ResponseStatusException e = assertThrows(
                ResponseStatusException.class,
                () -> controller().confirm(alice, "ABCD-EFGH", new ConfirmRequest("acme", "web")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void deny_forwardsTheCodeAndAnswersDenied() {
        assertEquals(
                Map.of("status", "denied"),
                controller().deny(alice, "ABCD-EFGH").data());
        verify(service).deny("ABCD-EFGH");
    }

    private static TenantPathResolver.Resolved resolved(String role) {
        return new TenantPathResolver.Resolved(
                new Organization("org_1", null, "acme", "Acme", NOW.toString(), null, null),
                new Project("prj_1", "org_1", "web", "Web", null, NOW.toString(), null, null, true, null),
                role);
    }

    /** A clock a test can move forward, for the limiter's minute windows. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

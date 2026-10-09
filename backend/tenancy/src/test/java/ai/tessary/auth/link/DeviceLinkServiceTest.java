// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.link.DeviceLinkService.PollOutcome;
import ai.tessary.auth.link.DeviceLinkService.View;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import at.favre.lib.crypto.bcrypt.BCrypt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * The device-link state machine: what a plugin's poll answers for each row state, when a token is
 * minted (once, and only for the poll that wins the claim), and the browser-side view, confirm and
 * deny. The repository is mocked because its atomic transitions are what this class relies on and
 * {@code DeviceLinkRepositoryTest} proves them against Postgres; here they are fed as return values.
 */
@ExtendWith(MockitoExtension.class)
class DeviceLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
    /** A syntactically valid device code: the "link_" prefix, 8 lookup chars, then the secret tail. */
    private static final String CODE = "link_ABCDEFGH-the-secret-tail";

    private static final String PREFIX = "link_ABCDEFGH";
    /** Cost 4 keeps the suite fast; verification reads the cost from the hash, so it is the same check. */
    private static final String HASH = BCrypt.withDefaults().hashToString(4, CODE.toCharArray());

    @Mock
    DeviceLinkRepository links;

    @Mock
    ApiKeyService mcpTokens;

    @Mock
    ProjectRepository projects;

    @Mock
    OrganizationRepository orgs;

    private DeviceLinkService service() {
        return new DeviceLinkService(links, mcpTokens, projects, orgs, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DeviceLink row(String status, Instant expiresAt, @Nullable Instant lastPolledAt) {
        return new DeviceLink(
                "dl_1",
                PREFIX,
                HASH,
                "ABCD-EFGH",
                status,
                "laptop",
                "org_1",
                "prj_1",
                "usr_1",
                null,
                NOW.minusSeconds(60).toString(),
                expiresAt.toString(),
                lastPolledAt == null ? null : lastPolledAt.toString(),
                4);
    }

    private static DeviceLink live(String status) {
        return row(status, NOW.plusSeconds(300), null);
    }

    @Test
    void start_storesOnlyAHashThatVerifiesTheReturnedCode_andAPendingPollAnswersPending() {
        ArgumentCaptor<DeviceLink> inserted = ArgumentCaptor.forClass(DeviceLink.class);
        doNothing().when(links).insert(inserted.capture());

        DeviceLinkService.Started s = service().start("laptop");

        DeviceLink r = inserted.getValue();
        assertEquals(NOW.plus(DeviceLinkService.TTL), s.expiresAt());
        assertEquals(s.userCode(), r.userCode());
        assertTrue(
                s.userCode().matches("[ABCDEFGHJKMNPQRSTVWXYZ2-9]{4}-[ABCDEFGHJKMNPQRSTVWXYZ2-9]{4}"),
                "user codes are 4-4 from the unambiguous alphabet: " + s.userCode());
        assertEquals(
                new DeviceLink(
                        r.id(),
                        s.deviceCode().substring(0, 13),
                        r.deviceCodeHash(),
                        s.userCode(),
                        DeviceLink.PENDING,
                        "laptop",
                        null,
                        null,
                        null,
                        null,
                        NOW.toString(),
                        NOW.plus(DeviceLinkService.TTL).toString(),
                        null,
                        0),
                r);
        assertFalse(r.deviceCodeHash().contains(s.deviceCode()), "the device code must never be stored");

        // The stored hash is the credential: polling the returned code against that very row must
        // pass verification and reach the pending answer.
        when(links.findByPrefix(r.deviceCodePrefix())).thenReturn(Optional.of(r));
        assertEquals(PollOutcome.of("authorization_pending"), service().poll(s.deviceCode()));
        verify(links).recordPoll(r.id(), NOW.toString(), 1);
    }

    @Test
    void start_retriesAUserCodeCollisionThenGivesUpAfterFiveAttempts() {
        doThrow(new DuplicateKeyException("user_code"))
                .doThrow(new DuplicateKeyException("user_code"))
                .doNothing()
                .when(links)
                .insert(any());
        service().start(null);
        verify(links, times(3)).insert(any());

        doThrow(new DuplicateKeyException("user_code")).when(links).insert(any());
        TessaryException e =
                assertThrows(TessaryException.class, () -> service().start(null));
        assertEquals(CommonError.INTERNAL, e.error());
        verify(links, times(3 + 5)).insert(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"tsk_ABCDEFGH-not-a-link-code", "link_short"})
    void poll_malformedCodeIsExpiredWithoutALookup(String code) {
        assertEquals(PollOutcome.of("expired"), service().poll(code));
    }

    @Test
    void poll_unknownPrefixOrWrongSecretIsExpired() {
        when(links.findByPrefix(PREFIX)).thenReturn(Optional.empty()).thenReturn(Optional.of(live("confirmed")));
        assertEquals(PollOutcome.of("expired"), service().poll(CODE));
        // Same 13-char prefix, different secret: the prefix is only a lookup key, never the credential.
        assertEquals(PollOutcome.of("expired"), service().poll(PREFIX + "-a-guessed-tail"));
        verify(links, never()).beginClaim(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"claimed:already_claimed", "denied:denied"})
    void poll_terminalRowsAnswerTheirStateAndRecordNoPoll(String statusAndAnswer) {
        String[] p = statusAndAnswer.split(":");
        when(links.findByPrefix(PREFIX)).thenReturn(Optional.of(live(p[0])));
        assertEquals(PollOutcome.of(p[1]), service().poll(CODE));
        verify(links, never()).recordPoll(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void poll_pastExpiryMarksThePendingRowExpiredOnce() {
        DeviceLink stale = row(DeviceLink.PENDING, NOW.minusSeconds(1), null);
        DeviceLink alreadyMarked = row(DeviceLink.EXPIRED, NOW.minusSeconds(1), null);
        when(links.findByPrefix(PREFIX)).thenReturn(Optional.of(stale)).thenReturn(Optional.of(alreadyMarked));

        assertEquals(PollOutcome.of("expired"), service().poll(CODE));
        assertEquals(PollOutcome.of("expired"), service().poll(CODE));

        verify(links, times(1)).markStatus("dl_1", DeviceLink.EXPIRED);
    }

    @Test
    void poll_insideTheAdvertisedIntervalIsSlowDown_andAtTheIntervalIsServed() {
        int interval = DeviceLinkService.POLL_INTERVAL_SECONDS;
        when(links.findByPrefix(PREFIX))
                .thenReturn(Optional.of(row(DeviceLink.PENDING, NOW.plusSeconds(300), NOW.minusSeconds(interval - 1))))
                .thenReturn(Optional.of(row(DeviceLink.PENDING, NOW.plusSeconds(300), NOW.minusSeconds(interval))));

        assertEquals(PollOutcome.of("slow_down"), service().poll(CODE));
        verify(links, never()).recordPoll(any(), any(), org.mockito.ArgumentMatchers.anyInt());

        assertEquals(PollOutcome.of("authorization_pending"), service().poll(CODE));
        verify(links).recordPoll("dl_1", NOW.toString(), 5);
    }

    @Test
    void poll_confirmedButLosingTheClaimMintsNothing() {
        when(links.findByPrefix(PREFIX)).thenReturn(Optional.of(live(DeviceLink.CONFIRMED)));
        when(links.beginClaim("dl_1")).thenReturn(0);

        assertEquals(PollOutcome.of("already_claimed"), service().poll(CODE));
        verify(mcpTokens, never()).issue(any(), any(), any());
    }

    @Test
    void poll_claimedLinkWhoseProjectOrOrgIsGoneExpiresWithoutMinting() {
        when(links.findByPrefix(PREFIX)).thenReturn(Optional.of(live(DeviceLink.CONFIRMED)));
        when(links.beginClaim("dl_1")).thenReturn(1);
        when(projects.findById("prj_1")).thenReturn(Optional.empty()).thenReturn(Optional.of(project()));
        when(orgs.findById("org_1")).thenReturn(Optional.of(org())).thenReturn(Optional.empty());

        assertEquals(PollOutcome.of("expired"), service().poll(CODE));
        assertEquals(PollOutcome.of("expired"), service().poll(CODE));

        verify(links, times(2)).markStatus("dl_1", DeviceLink.EXPIRED);
        verify(mcpTokens, never()).issue(any(), any(), any());
    }

    @Test
    void poll_winningTheClaimMintsOneTokenForTheConfirmingUserAndAttachesIt() {
        when(links.findByPrefix(PREFIX)).thenReturn(Optional.of(live(DeviceLink.CONFIRMED)));
        when(links.beginClaim("dl_1")).thenReturn(1);
        when(projects.findById("prj_1")).thenReturn(Optional.of(project()));
        when(orgs.findById("org_1")).thenReturn(Optional.of(org()));
        ApiKey key = new ApiKey(
                "tok_1", "prj_1", "usr_1", "n", "tsk_abc", "h", NOW.toString(), null, null, "admin", null, null, null);
        when(mcpTokens.issue("prj_1", "usr_1", "Claude Code link ABCD-EFGH"))
                .thenReturn(new ApiKeyService.Issued(key, "tsk_plaintext"));

        assertEquals(
                new PollOutcome("ready", "tsk_plaintext", "acme", "web"),
                service().poll(CODE));
        verify(links).setToken("dl_1", "tok_1");
    }

    @Test
    void view_reportsExpiryForAnUnclaimedLinkButKeepsClaimed() {
        when(links.findByUserCode("ABCD-EFGH"))
                .thenReturn(Optional.of(live(DeviceLink.PENDING)))
                .thenReturn(Optional.of(row(DeviceLink.PENDING, NOW.minusSeconds(1), null)))
                .thenReturn(Optional.of(row(DeviceLink.CLAIMED, NOW.minusSeconds(1), null)));
        when(links.findByUserCode("NOPE-NOPE")).thenReturn(Optional.empty());
        String live = NOW.plusSeconds(300).toString();
        String past = NOW.minusSeconds(1).toString();

        assertEquals(
                Optional.of(new View("ABCD-EFGH", "laptop", "pending", live)),
                service().view("ABCD-EFGH"));
        assertEquals(
                Optional.of(new View("ABCD-EFGH", "laptop", "expired", past)),
                service().view("ABCD-EFGH"));
        assertEquals(
                Optional.of(new View("ABCD-EFGH", "laptop", "claimed", past)),
                service().view("ABCD-EFGH"));
        assertEquals(Optional.empty(), service().view("NOPE-NOPE"));
    }

    @Test
    void confirm_bindsOnlyALiveLinkAndReportsTheConditionalUpdate() {
        when(links.findByUserCode("NOPE-NOPE")).thenReturn(Optional.empty());
        when(links.findByUserCode("ABCD-EFGH"))
                .thenReturn(Optional.of(row(DeviceLink.PENDING, NOW.minusSeconds(1), null)))
                .thenReturn(Optional.of(live(DeviceLink.PENDING)));
        when(links.markConfirmed("dl_1", "org_9", "prj_9", "usr_9"))
                .thenReturn(1)
                .thenReturn(0);

        assertFalse(service().confirm("NOPE-NOPE", "org_9", "prj_9", "usr_9"));
        assertFalse(service().confirm("ABCD-EFGH", "org_9", "prj_9", "usr_9"), "an expired link cannot be bound");
        assertTrue(service().confirm("ABCD-EFGH", "org_9", "prj_9", "usr_9"));
        assertFalse(
                service().confirm("ABCD-EFGH", "org_9", "prj_9", "usr_9"),
                "a link no longer pending (a racing confirm or deny won) must not report success");
        verify(links, times(2)).markConfirmed("dl_1", "org_9", "prj_9", "usr_9");
    }

    @Test
    void deny_succeedsOnlyWhileThePendingTransitionApplies() {
        when(links.findByUserCode("NOPE-NOPE")).thenReturn(Optional.empty());
        when(links.findByUserCode("ABCD-EFGH")).thenReturn(Optional.of(live(DeviceLink.PENDING)));
        when(links.transitionIf("dl_1", DeviceLink.PENDING, DeviceLink.DENIED))
                .thenReturn(1)
                .thenReturn(0);

        assertFalse(service().deny("NOPE-NOPE"));
        assertTrue(service().deny("ABCD-EFGH"));
        assertFalse(service().deny("ABCD-EFGH"));
    }

    private static Project project() {
        return new Project("prj_1", "org_1", "web", "Web", null, NOW.toString(), null, null, true, null);
    }

    private static Organization org() {
        return new Organization("org_1", null, "acme", "Acme", NOW.toString(), null, null);
    }
}

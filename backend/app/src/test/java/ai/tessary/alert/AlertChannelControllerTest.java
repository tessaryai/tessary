// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertChannelDtos.ChannelView;
import ai.tessary.alert.AlertChannelDtos.DeleteResponse;
import ai.tessary.alert.AlertChannelDtos.DeliveryAttemptView;
import ai.tessary.alert.AlertChannelDtos.UpsertChannelRequest;
import ai.tessary.auth.TenantContext;
import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The alert-channel API against the real schema. Credentials are write-only, so the bugs are an update
 * that silently wipes or fails to replace the stored secret, a member (rather than an owner or the
 * project's own token) changing where alerts go, a Slack channel slipping past the Slack gate, and a
 * delivery log that cannot be read back.
 */
@SpringBootTest
class AlertChannelControllerTest {

    @Autowired
    TenantService tenants;

    @Autowired
    OrgMembershipRepository memberships;

    @Autowired
    AlertChannelController controller;

    @Autowired
    AlertChannelRepository channels;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    AlertEventRepository events;

    @Autowired
    DeliveryAttemptRepository attempts;

    @Autowired
    SecretBox secretBox;

    @Autowired
    CapabilityFixture capabilities;

    @Test
    void anUpdateKeepsTheStoredSecretUnlessANewConfigIsGiven() {
        var fix = TenantFixture.bootstrap(tenants, "channel-crud");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        String pid = fix.project().id();

        ChannelView created = requireNonNull(controller
                .create(owner, org, project, new UpsertChannelRequest("webhook", "ops", null, Map.of("url", "u1")))
                .data());
        assertEquals(
                new ChannelView(created.id(), "webhook", "ops", true, true, created.createdAt(), created.updatedAt()),
                created,
                "enabled unless said otherwise, and only an indicator that credentials exist");
        assertEquals(List.of(created), controller.list(owner, org, project).data());
        String sealed = channels.find(pid, created.id()).orElseThrow().configEnc();

        ChannelView renamed = requireNonNull(controller
                .update(
                        owner,
                        org,
                        project,
                        created.id(),
                        new UpsertChannelRequest("webhook", "ops-2", false, Map.of()))
                .data());
        controller.update(owner, org, project, created.id(), new UpsertChannelRequest("webhook", "ops-2", false, null));
        assertEquals(
                new ChannelView(
                        created.id(), "webhook", "ops-2", false, true, created.createdAt(), renamed.updatedAt()),
                renamed);
        assertEquals(
                sealed,
                channels.find(pid, created.id()).orElseThrow().configEnc(),
                "an absent or empty config keeps the stored secret");

        controller.update(
                owner,
                org,
                project,
                created.id(),
                new UpsertChannelRequest("webhook", "ops-2", true, Map.of("url", "u2")));
        String resealed = channels.find(pid, created.id()).orElseThrow().configEnc();
        assertNotEquals(sealed, resealed);
        assertEquals("{\"url\":\"u2\"}", secretBox.open(resealed), "a new config replaces the secret");

        assertEquals(
                new DeleteResponse(true),
                controller.delete(owner, org, project, created.id()).data());
        assertEquals(
                new DeleteResponse(false),
                controller.delete(owner, org, project, created.id()).data());
        assertEquals(
                AlertError.CHANNEL_NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> controller.update(
                                        owner,
                                        org,
                                        project,
                                        created.id(),
                                        new UpsertChannelRequest("webhook", "x", true, null)))
                        .error());
    }

    /** A member may read the channels but not redirect alerts; the project's own token may. */
    @Test
    void onlyAnOwnerOrTheProjectsTokenMayChangeWhereAlertsGo() {
        var fix = TenantFixture.bootstrap(tenants, "channel-rbac");
        Principal member = tenants.upsertUserFromWorkos(
                "user_channel_member_" + System.nanoTime(),
                "channel-member+" + System.nanoTime() + "@example.com",
                "m",
                null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), member.id(), OrgMembership.MEMBER, Instant.now().toString()));
        TenantContext session = new TenantContext(member.id(), member.email(), null, null, null, null);
        TenantContext token = new TenantContext(
                fix.user().id(), null, fix.org().id(), fix.project().id(), OrgMembership.MEMBER, "tok_" + Ids.ulid());
        UpsertChannelRequest req = new UpsertChannelRequest("webhook", "ops", true, Map.of("url", "u"));
        String org = fix.org().slug();
        String project = fix.project().slug();

        ResponseStatusException refused =
                assertThrows(ResponseStatusException.class, () -> controller.create(session, org, project, req));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals(List.of(), controller.list(session, org, project).data(), "reading is open to members");

        ChannelView made =
                requireNonNull(controller.create(token, org, project, req).data());
        assertEquals(List.of(made), controller.list(session, org, project).data());
    }

    /**
     * The Slack gate is checked at the write, whatever the kind's spelling, and only for Slack: an org with
     * Slack adds the channel, and once Slack is withheld neither a new one nor a switch to one gets through.
     */
    @Test
    void aSlackChannelIsWrittenOnlyWhileTheOrgHasSlack() {
        var fix = TenantFixture.bootstrap(tenants, "channel-slack-gate");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        UpsertChannelRequest slack = new UpsertChannelRequest(" Slack ", "team", true, Map.of("url", "u"));
        ChannelView allowed =
                requireNonNull(controller.create(owner, org, project, slack).data());
        assertEquals("slack", allowed.kind());

        capabilities.withhold(fix.org().id(), Capability.SLACK);
        ChannelView webhook = requireNonNull(controller
                .create(owner, org, project, new UpsertChannelRequest("webhook", "ops", true, Map.of("url", "u")))
                .data());

        assertEquals(
                CapabilityError.DISABLED,
                assertThrows(TessaryException.class, () -> controller.create(owner, org, project, slack))
                        .error());
        assertEquals(
                CapabilityError.DISABLED,
                assertThrows(TessaryException.class, () -> controller.update(owner, org, project, webhook.id(), slack))
                        .error());
        assertEquals(
                List.of(allowed, webhook), controller.list(owner, org, project).data());
    }

    /** The delivery log is readable, capped, and a non-positive or absent limit is the default page. */
    @Test
    void theDeliveryLogReadsBackEachAttemptsOutcome() {
        var fix = TenantFixture.bootstrap(tenants, "channel-log");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        String pid = fix.project().id();
        String channel = requireNonNull(controller
                        .create(
                                owner,
                                org,
                                project,
                                new UpsertChannelRequest("webhook", "ops", true, Map.of("url", "u")))
                        .data())
                .id();
        String eventA = firedEvent(pid, "2026-01-15T09:00:00Z");
        String eventB = firedEvent(pid, "2026-01-15T10:00:00Z");
        attempts.claim("att_a", eventA, channel, pid);
        attempts.markDelivered("att_a", 202);
        attempts.claim("att_b", eventB, channel, pid);
        attempts.markFailed("att_b", 500, "webhook returned HTTP 500");

        List<DeliveryAttemptView> all =
                requireNonNull(controller.deliveries(owner, org, project, null).data());

        assertEquals(
                Map.of(
                        "att_a", List.of(eventA, channel, "delivered", "202", ""),
                        "att_b", List.of(eventB, channel, "failed", "500", "webhook returned HTTP 500")),
                all.stream()
                        .collect(Collectors.toMap(
                                DeliveryAttemptView::id,
                                v -> List.of(
                                        v.alertEventId(),
                                        v.channelId(),
                                        v.status(),
                                        String.valueOf(v.httpStatus()),
                                        v.error() == null ? "" : v.error()))));
        assertEquals(
                Set.of(true),
                all.stream().map(v -> v.completedAt() != null).collect(Collectors.toSet()),
                "both completed");
        assertEquals(all, controller.deliveries(owner, org, project, 0).data());
        assertEquals(all, controller.deliveries(owner, org, project, 5_000).data());
        assertEquals(
                1,
                requireNonNull(controller.deliveries(owner, org, project, 1).data())
                        .size());
    }

    private String firedEvent(String pid, String at) {
        String now = Instant.now().toString();
        String ruleId = Ids.ulid();
        rules.insert(new AlertRuleRow(
                ruleId,
                pid,
                AlertRuleRow.RuleType.THRESHOLD,
                "r",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                "{}",
                now,
                now));
        String id = Ids.ulid();
        events.insertIfAbsent(new AlertEventRow(
                id,
                pid,
                ruleId,
                null,
                AlertRuleRow.RuleType.THRESHOLD,
                null,
                AlertEventRow.State.FIRING,
                at,
                at,
                1,
                null,
                null,
                null,
                at,
                at));
        return id;
    }

    private static TenantContext owner(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }
}

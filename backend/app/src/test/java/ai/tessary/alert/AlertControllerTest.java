// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertDtos.AlertEventView;
import ai.tessary.alert.AlertDtos.AlertRuleView;
import ai.tessary.alert.AlertDtos.PolicyView;
import ai.tessary.alert.AlertDtos.SetEnabledRequest;
import ai.tessary.alert.AlertDtos.SnoozeRequest;
import ai.tessary.alert.AlertDtos.UpsertAlertRuleRequest;
import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The alert-rule API against the real schema. Anchors and switches stop a partner being re-paged about everything
 * since the rule was made, so the bugs are a reconfigure resetting them, a rule the worker cannot evaluate, a policy
 * reading back differently, and a fired-alert filter leaking another rule's or project's firings.
 */
@SpringBootTest
class AlertControllerTest {

    @Autowired
    TenantService tenants;

    @Autowired
    AlertController controller;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    AlertEventRepository events;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    AlertRuleRepository ruleRepo;

    @Autowired
    ai.tessary.auth.TenantPathResolver resolver;

    @Autowired
    ai.tessary.plan.CapabilityService capabilityService;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    /**
     * Recreating the seeded case-opened rule anchors it at creation, and reconfiguring keeps the anchor, enabled
     * flag, and snooze; a reset anchor re-fires every case since.
     */
    @Test
    void aCaseOpenedRuleIsReconfiguredWithoutLosingItsAnchorSwitchOrSnooze() {
        var fix = TenantFixture.bootstrap(tenants, "alert-case-rule");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();

        AlertRuleView seeded =
                requireNonNull(controller.listRules(owner, org, project).data()).get(0);
        assertEquals(AlertService.DEFAULT_CASE_RULE_NAME, seeded.name());
        assertEquals(new PolicyView(0, null, null, "UTC"), seeded.policy(), "seeded as immediate, no quiet window");
        assertEquals(
                seeded, controller.deleteRule(owner, org, project, seeded.id()).data());
        assertEquals(List.of(), controller.listRules(owner, org, project).data());
        assertError(AlertError.RULE_NOT_FOUND, () -> controller.getRule(owner, org, project, seeded.id()));
        assertError(AlertError.RULE_NOT_FOUND, () -> controller.deleteRule(owner, org, project, seeded.id()));

        PolicyView quietNights = new PolicyView(900, "22:00", "08:00", "Europe/Berlin");
        AlertRuleView created = requireNonNull(controller
                .upsertRule(owner, org, project, caseReq("Page me", quietNights))
                .data());
        assertEquals(quietNights, created.policy());
        assertEquals(created.createdAt(), created.lastEvaluatedAt(), "a new rule is anchored at its creation");
        assertEquals(true, created.enabled());

        controller.snoozeRule(owner, org, project, created.id(), new SnoozeRequest("2030-01-01T00:00:00Z"));
        controller.setRuleEnabled(owner, org, project, created.id(), new SetEnabledRequest(false));
        AlertRuleView reconfigured = requireNonNull(controller
                .upsertRule(owner, org, project, caseReq("Page us", null))
                .data());

        assertEquals(
                new AlertRuleView(
                        created.id(),
                        AlertRuleRow.RuleType.CASE_OPENED,
                        "Page us",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "warn",
                        null,
                        null,
                        false,
                        "2030-01-01T00:00:00Z",
                        created.lastEvaluatedAt(),
                        null,
                        null,
                        new PolicyView(0, null, null, "UTC"),
                        created.createdAt(),
                        reconfigured.updatedAt()),
                reconfigured);
        assertEquals(
                reconfigured,
                controller.getRule(owner, org, project, created.id()).data());
    }

    /** A rule the worker cannot evaluate is refused by name, and nothing is half-written. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unrunnableRules")
    void aRuleTheWorkerCannotEvaluateIsRefusedByName(
            String why,
            String ruleType,
            @Nullable String classifier,
            @Nullable String basis,
            @Nullable String briefCron,
            ErrorCode expected) {
        var fix = TenantFixture.bootstrap(tenants, "alert-refuse");
        String classifierId =
                "seeded".equals(classifier) ? seedClassifier(fix.project().id()) : classifier;
        UpsertAlertRuleRequest req = new UpsertAlertRuleRequest(
                ruleType, "r", classifierId, null, basis, null, null, null, null, null, null, briefCron, null);

        assertError(
                expected,
                () -> controller.upsertRule(
                        owner(fix), fix.org().slug(), fix.project().slug(), req));
        assertEquals(
                List.of(AlertRuleRow.RuleType.CASE_OPENED),
                requireNonNull(controller
                                .listRules(
                                        owner(fix),
                                        fix.org().slug(),
                                        fix.project().slug())
                                .data())
                        .stream()
                        .map(AlertRuleView::ruleType)
                        .toList(),
                "only the seeded rule exists");
    }

    static Stream<Arguments> unrunnableRules() {
        return Stream.of(
                Arguments.of(
                        "a rule type nothing evaluates", "anomaly", null, null, null, AlertError.UNSUPPORTED_RULE_TYPE),
                Arguments.of(
                        "threshold with no classifier",
                        "threshold",
                        null,
                        "event_count",
                        null,
                        AlertError.MISSING_CLASSIFIER),
                Arguments.of(
                        "threshold with a blank classifier",
                        "threshold",
                        " ",
                        "event_count",
                        null,
                        AlertError.MISSING_CLASSIFIER),
                Arguments.of(
                        "threshold on a classifier not in the project",
                        "threshold",
                        "cls_nope",
                        "event_count",
                        null,
                        ClassifierError.NOT_FOUND),
                Arguments.of("threshold with no basis", "threshold", "seeded", null, null, AlertError.INVALID_BASIS),
                Arguments.of(
                        "threshold with an unknown basis",
                        "threshold",
                        "seeded",
                        "median",
                        null,
                        AlertError.INVALID_BASIS),
                Arguments.of("brief with no cron", "brief", null, null, null, AlertError.MISSING_CRON),
                Arguments.of("brief with a blank cron", "brief", null, null, "  ", AlertError.MISSING_CRON));
    }

    /** Defaults on create; a second upsert reconfigures in place with the same id and creation time. */
    @Test
    void thresholdAndRollupRulesTakeDefaultsAndAreReconfiguredInPlace() {
        var fix = TenantFixture.bootstrap(tenants, "alert-upsert");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        String cls = seedClassifier(fix.project().id());

        AlertRuleView threshold = requireNonNull(controller
                .upsertRule(
                        owner,
                        org,
                        project,
                        new UpsertAlertRuleRequest(
                                "threshold",
                                "t1",
                                cls,
                                "span",
                                "event_count",
                                null,
                                null,
                                "call_site",
                                5,
                                "warn",
                                null,
                                null,
                                null))
                .data());
        assertEquals(
                new AlertRuleView(
                        threshold.id(),
                        "threshold",
                        "t1",
                        cls,
                        "span",
                        "event_count",
                        1,
                        86_400,
                        "call_site",
                        5,
                        "warn",
                        null,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        threshold.createdAt(),
                        threshold.updatedAt()),
                threshold,
                "threshold defaults to 1 over a rolling day");
        AlertRuleView retuned = requireNonNull(controller
                .upsertRule(
                        owner,
                        org,
                        project,
                        new UpsertAlertRuleRequest(
                                "threshold",
                                "t2",
                                cls,
                                null,
                                "distinct_users",
                                0,
                                3600,
                                null,
                                null,
                                "page",
                                null,
                                null,
                                null))
                .data());
        assertEquals(
                new AlertRuleView(
                        threshold.id(),
                        "threshold",
                        "t2",
                        cls,
                        null,
                        "distinct_users",
                        1,
                        3600,
                        null,
                        null,
                        "page",
                        null,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        threshold.createdAt(),
                        retuned.updatedAt()),
                retuned,
                "a zero threshold is floored at one");

        AlertRuleView brief = requireNonNull(controller
                .upsertRule(owner, org, project, rollupReq("brief", null, "0 0 9 * * MON"))
                .data());
        assertEquals("0 0 9 * * MON", brief.briefCron());
        assertNull(brief.digestCron(), "a brief carries no digest cron");
        AlertRuleView digest = requireNonNull(controller
                .upsertRule(owner, org, project, rollupReq("digest", "0 0 8 * * *", null))
                .data());
        AlertRuleView redigest = requireNonNull(controller
                .upsertRule(owner, org, project, rollupReq("digest", "0 30 7 * * *", "0 0 9 * * MON"))
                .data());
        assertEquals(
                new AlertRuleView(
                        digest.id(),
                        "digest",
                        "daily",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "0 30 7 * * *",
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        digest.createdAt(),
                        redigest.updatedAt()),
                redigest,
                "a digest ignores a brief cron and keeps its id on reconfigure");
        assertEquals(
                List.of("case_opened", "threshold", "brief", "digest"),
                requireNonNull(controller.listRules(owner, org, project).data()).stream()
                        .map(AlertRuleView::ruleType)
                        .toList());
    }

    /** Two racing deletes: the loser gets a 404, not a 200 for a rule it did not delete. */
    @Test
    void theLoserOfTwoConcurrentDeletesIsNotFound() {
        var fix = TenantFixture.bootstrap(tenants, "alert-delete-race");
        String ruleId = requireNonNull(controller
                        .listRules(owner(fix), fix.org().slug(), fix.project().slug())
                        .data())
                .get(0)
                .id();
        AlertService racing = new AlertService(ruleRepo, events, classifiers, mapper) {
            @Override
            public boolean deleteRule(String projectId, String id) {
                super.deleteRule(projectId, id); // the other request's delete lands first
                return super.deleteRule(projectId, id);
            }
        };
        AlertController losing = new AlertController(racing, resolver, capabilityService, mapper);

        assertError(
                AlertError.RULE_NOT_FOUND,
                () -> losing.deleteRule(
                        owner(fix), fix.org().slug(), fix.project().slug(), ruleId));
    }

    @Test
    void switchingOrSnoozingARuleThatDoesNotExistIsNotFound() {
        var fix = TenantFixture.bootstrap(tenants, "alert-missing");
        String org = fix.org().slug();
        String project = fix.project().slug();

        assertError(
                AlertError.RULE_NOT_FOUND,
                () -> controller.setRuleEnabled(owner(fix), org, project, "rule_nope", new SetEnabledRequest(true)));
        assertError(
                AlertError.RULE_NOT_FOUND,
                () -> controller.snoozeRule(owner(fix), org, project, "rule_nope", new SnoozeRequest(null)));
    }

    /**
     * Filtered by rule, by classifier, or not at all, newest first and capped; no leaked firings, and a zero limit
     * still returns rows.
     */
    @Test
    void firedAlertsAreReadByRuleByClassifierOrForTheWholeProjectNewestFirst() {
        var fix = TenantFixture.bootstrap(tenants, "alert-fired");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        String pid = fix.project().id();
        String cls = seedClassifier(pid);
        String thresholdRule = requireNonNull(controller
                        .upsertRule(
                                owner,
                                org,
                                project,
                                new UpsertAlertRuleRequest(
                                        "threshold",
                                        "t",
                                        cls,
                                        null,
                                        "event_count",
                                        3,
                                        60,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))
                        .data())
                .id();
        String digestRule = requireNonNull(controller
                        .upsertRule(owner, org, project, rollupReq("digest", "0 0 8 * * *", null))
                        .data())
                .id();
        AlertEventRow first = fired(pid, thresholdRule, cls, "event_count", "2026-01-15T09:00:00Z", 4, 3);
        AlertEventRow second = fired(pid, thresholdRule, cls, "event_count", "2026-01-15T10:00:00Z", 5, 3);
        AlertEventRow rollup = fired(pid, digestRule, null, null, "2026-01-15T11:00:00Z", 9, null);
        AlertEventView firstView = new AlertEventView(
                first.id(),
                thresholdRule,
                cls,
                "threshold",
                "event_count",
                "firing",
                "2026-01-15T09:00:00Z",
                "2026-01-15T09:01:00Z",
                4,
                3,
                "{\"n\":4}",
                "2026-01-15T09:00:00Z");

        assertEquals(
                List.of(second.id(), first.id()),
                ids(controller
                        .fired(owner, org, project, thresholdRule, null, 200)
                        .data()));
        assertEquals(
                firstView,
                requireNonNull(controller
                                .fired(owner, org, project, thresholdRule, null, 200)
                                .data())
                        .get(1));
        assertEquals(
                List.of(second.id(), first.id()),
                ids(controller.fired(owner, org, project, "", cls, 200).data()));
        assertEquals(
                List.of(rollup.id(), second.id(), first.id()),
                ids(controller.fired(owner, org, project, null, " ", 0).data()),
                "a non-positive limit is the default page, not an empty one");
        assertEquals(
                List.of(rollup.id(), second.id()),
                ids(controller.fired(owner, org, project, null, null, 2).data()));
        assertEquals(
                List.of(rollup.id(), second.id(), first.id()),
                ids(controller.fired(owner, org, project, null, null, 50_000).data()));
        assertError(ClassifierError.NOT_FOUND, () -> controller.fired(owner, org, project, null, "cls_nope", 10));
    }

    /** The whole surface is behind the alerts capability. */
    @Test
    void anOrgWithoutAlertsCannotReachItsRules() {
        var fix = TenantFixture.bootstrap(tenants, "alert-gated");
        capabilities.withhold(fix.org().id(), Capability.ALERTS);

        assertError(
                CapabilityError.DISABLED,
                () -> controller.listRules(
                        owner(fix), fix.org().slug(), fix.project().slug()));
    }

    private static List<String> ids(@Nullable List<AlertEventView> views) {
        return requireNonNull(views).stream().map(AlertEventView::id).toList();
    }

    private static void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(TessaryException.class, call).error());
    }

    private static TenantContext owner(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }

    private static UpsertAlertRuleRequest caseReq(String name, @Nullable PolicyView policy) {
        return new UpsertAlertRuleRequest(
                "case_opened", name, null, null, null, null, null, null, null, "warn", null, null, policy);
    }

    private static UpsertAlertRuleRequest rollupReq(
            String ruleType, @Nullable String digestCron, @Nullable String briefCron) {
        return new UpsertAlertRuleRequest(
                ruleType, "daily", null, null, null, null, null, null, null, null, digestCron, briefCron, null);
    }

    private AlertEventRow fired(
            String pid,
            String ruleId,
            @Nullable String cls,
            @Nullable String basis,
            String at,
            int value,
            @Nullable Integer threshold) {
        String end = Instant.parse(at).plusSeconds(60).toString();
        AlertEventRow row = new AlertEventRow(
                Ids.ulid(),
                pid,
                ruleId,
                cls,
                cls == null ? "digest" : "threshold",
                basis,
                AlertEventRow.State.FIRING,
                at,
                end,
                value,
                threshold,
                "{\"n\":" + value + "}",
                null,
                at,
                at);
        events.insertIfAbsent(row);
        return row;
    }

    private String seedClassifier(String pid) {
        String now = Instant.now().toString();
        String id = Ids.ulid();
        classifiers.insert(new ClassifierRow(
                id,
                pid,
                "frustration-" + id,
                "frustration",
                null,
                "keyword",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));
        return id;
    }
}

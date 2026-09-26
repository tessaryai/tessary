// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.config.AlertProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The heartbeat's decisions, through fakes. One heartbeat serves every project, so one failure must not silence the
 * rest: a failed roll-up scan skipping case notifications, a failed case scan throwing, a corrupt anchor stopping
 * later rules, or a bad cron firing. Also: an anchor moving with nothing delivered, and a roll-up published twice or
 * on the wrong cron.
 */
@ExtendWith(MockitoExtension.class)
class AlertWorkerTest {

    private static final String CREATED = "2026-01-15T09:00:00Z";

    @Mock
    AlertRuleRepository rules;

    @Mock
    AlertEventRepository events;

    @Mock
    AlertAssembler assembler;

    @Mock
    CaseAlertEvaluator caseAlerts;

    @Mock
    ClassifierService classifiers;

    @Mock
    ProjectRepository projects;

    private final List<Object> published = new ArrayList<>();

    @Test
    void aFailedRollupScanStillDeliversCasesAndOneBrokenCaseRuleDoesNotStopTheNext() {
        when(rules.listEnabledRollups()).thenThrow(new IllegalStateException("connection reset"));
        AlertRuleRow broken = caseRule("r_broken", "not-an-instant");
        AlertRuleRow healthy = caseRule("r_ok", CREATED);
        when(rules.listEnabled(AlertRuleRow.RuleType.CASE_OPENED)).thenReturn(List.of(broken, healthy));
        when(projects.findById("p1")).thenReturn(Optional.of(project("p1")));
        when(classifiers.unavailableDetectorKinds("p1")).thenReturn(Set.of());
        AlertEventRow firing = firing("evt_case", AlertRuleRow.RuleType.CASE_OPENED, "2026-01-15T09:30:00Z");
        when(caseAlerts.due(eq(healthy), eq(Instant.parse(CREATED)), any(), eq(Set.of())))
                .thenReturn(List.of(firing));
        when(events.insertIfAbsent(firing)).thenReturn(true);

        worker().tick();

        assertEquals(List.of(new AlertFiredEvent(firing)), published);
        verify(rules).markEvaluated("r_ok", "2026-01-15T09:30:00Z");
    }

    @Test
    void aFailedCaseScanLeavesDueRollupsDeliveredAndSkipsOnlyTheRulesThatCannotRun() {
        AlertRuleRow badCron = digestRule("r_cron", "p1", "not a cron", null);
        AlertRuleRow brokenAnchor = digestRule("r_anchor", "p1", "* * * * * *", "garbage");
        AlertRuleRow unentitled = digestRule("r_other", "p2", "* * * * * *", null);
        AlertRuleRow healthy = digestRule("r_ok", "p1", "* * * * * *", null);
        when(rules.listEnabledRollups()).thenReturn(List.of(badCron, brokenAnchor, unentitled, healthy));
        when(projects.findById("p1")).thenReturn(Optional.of(project("p1")));
        when(projects.findById("p2")).thenReturn(Optional.empty());
        AlertEventRow rollup = firing("evt_digest", AlertRuleRow.RuleType.DIGEST, CREATED);
        when(assembler.assemble(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0) == healthy ? Optional.of(rollup) : Optional.empty());
        when(events.insertIfAbsent(rollup)).thenReturn(true);
        when(rules.listEnabled(AlertRuleRow.RuleType.CASE_OPENED)).thenThrow(new IllegalStateException("timeout"));

        worker().tick();

        assertEquals(List.of(new AlertFiredEvent(rollup)), published);
        verify(rules).markRollupRan(eq("r_ok"), eq(AlertRuleRow.RuleType.DIGEST), anyString());
        verify(rules, never()).markRollupRan(eq("r_cron"), anyString(), anyString());
        verify(rules, never()).markRollupRan(eq("r_anchor"), anyString(), anyString());
        verify(rules, never()).markRollupRan(eq("r_other"), anyString(), anyString());
    }

    /**
     * Quiet hours, cadence, and snooze defer, never drop: a held tick leaves the anchor, so what opened meanwhile
     * goes out once the hold lifts.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("holds")
    void aHeldCaseRuleDeliversNothingAndKeepsItsAnchor(String why, String attributes, @Nullable String snoozedUntil) {
        AlertRuleRow held = new AlertRuleRow(
                "r_held",
                "p1",
                AlertRuleRow.RuleType.CASE_OPENED,
                "A case opens",
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
                snoozedUntil,
                Instant.now().minusSeconds(60).toString(),
                null,
                null,
                attributes,
                CREATED,
                CREATED);
        when(rules.listEnabledRollups()).thenReturn(List.of());
        when(rules.listEnabled(AlertRuleRow.RuleType.CASE_OPENED)).thenReturn(List.of(held));
        when(projects.findById("p1")).thenReturn(Optional.of(project("p1")));
        AlertEventRow wouldFire = firing("evt_case", AlertRuleRow.RuleType.CASE_OPENED, CREATED);
        CaseAlertEvaluator alwaysDue = new CaseAlertEvaluator(null, new AlertProperties(), new ObjectMapper()) {
            @Override
            public List<AlertEventRow> due(AlertRuleRow rule, Instant since, Instant now, Set<String> unavailable) {
                return List.of(wouldFire);
            }
        };

        worker(alwaysDue).tick();

        assertEquals(List.of(), published);
        verify(rules, never()).markEvaluated(anyString(), anyString());
    }

    static Stream<Arguments> holds() {
        return Stream.of(
                Arguments.of(
                        "inside a quiet window that spans the whole day",
                        "{\"quiet_from\":\"00:00\",\"quiet_to\":\"23:59:59.999999999\",\"quiet_zone\":\"UTC\"}",
                        null),
                Arguments.of("before the cadence has elapsed", "{\"cadence_seconds\":3600}", null),
                Arguments.of("snoozed", "{}", "2999-01-01T00:00:00Z"));
    }

    /**
     * A digest with no cron runs on the server default, a brief on its own cron and never without one, a snoozed
     * roll-up is consumed unassembled, and a period another backend wrote is not published twice.
     */
    @Test
    void rollupsFollowTheirOwnCronsSnoozeAndTheFirstWriteWins() {
        String longAgo = Instant.now().minusSeconds(8 * 86_400).toString();
        AlertRuleRow defaultDigest = rollup("r_default", AlertRuleRow.RuleType.DIGEST, null, null, longAgo);
        AlertRuleRow brief = rollup("r_brief", AlertRuleRow.RuleType.BRIEF, "0 0 9 * * MON", null, longAgo);
        AlertRuleRow unscheduled = rollup("r_nocron", AlertRuleRow.RuleType.BRIEF, " ", null, longAgo);
        AlertRuleRow snoozed =
                rollup("r_snoozed", AlertRuleRow.RuleType.DIGEST, "* * * * * *", "2999-01-01T00:00:00Z", longAgo);
        when(rules.listEnabledRollups()).thenReturn(List.of(defaultDigest, brief, unscheduled, snoozed));
        when(rules.listEnabled(AlertRuleRow.RuleType.CASE_OPENED)).thenReturn(List.of());
        when(projects.findById("p1")).thenReturn(Optional.of(project("p1")));
        AlertEventRow duplicate = firing("evt_dup", AlertRuleRow.RuleType.DIGEST, longAgo);
        AlertEventRow briefing = firing("evt_brief", AlertRuleRow.RuleType.BRIEF, longAgo);
        when(assembler.assemble(any(), any(), any())).thenAnswer(inv -> {
            AlertRuleRow rule = inv.getArgument(0);
            return Optional.of(
                    rule == brief
                            ? briefing
                            : rule == defaultDigest ? duplicate : firing("evt_" + rule.id(), rule.ruleType(), longAgo));
        });
        when(events.insertIfAbsent(any())).thenAnswer(inv -> inv.getArgument(0) != duplicate);

        worker().tick();

        assertEquals(List.of(new AlertFiredEvent(briefing)), published);
        verify(rules).markRollupRan(eq("r_default"), eq(AlertRuleRow.RuleType.DIGEST), anyString());
        verify(rules).markRollupRan(eq("r_brief"), eq(AlertRuleRow.RuleType.BRIEF), anyString());
        verify(rules).markRollupRan(eq("r_snoozed"), eq(AlertRuleRow.RuleType.DIGEST), anyString());
        verify(rules, never()).markRollupRan(eq("r_nocron"), anyString(), anyString());
    }

    private AlertWorker worker() {
        return worker(caseAlerts);
    }

    private AlertWorker worker(CaseAlertEvaluator caseEvaluator) {
        return new AlertWorker(
                rules,
                events,
                assembler,
                caseEvaluator,
                new ObjectMapper(),
                classifiers,
                new AlertProperties(),
                published::add,
                new CapabilityService((key, ctx) -> Optional.empty()),
                projects,
                new TraceMdcBridge(Tracer.NOOP));
    }

    private static Project project(String id) {
        return new Project(id, "org1", "bot", "Bot", null, CREATED, null, null, false, null);
    }

    private static AlertRuleRow caseRule(String id, String lastEvaluatedAt) {
        return new AlertRuleRow(
                id,
                "p1",
                AlertRuleRow.RuleType.CASE_OPENED,
                "A case opens",
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
                lastEvaluatedAt,
                null,
                null,
                "{}",
                CREATED,
                CREATED);
    }

    private static AlertRuleRow digestRule(String id, String projectId, String cron, @Nullable String lastDigestAt) {
        return new AlertRuleRow(
                id,
                projectId,
                AlertRuleRow.RuleType.DIGEST,
                "daily",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                cron,
                null,
                true,
                null,
                null,
                lastDigestAt,
                null,
                "{}",
                CREATED,
                CREATED);
    }

    private static AlertRuleRow rollup(
            String id, String ruleType, @Nullable String cron, @Nullable String snoozedUntil, String created) {
        boolean digest = AlertRuleRow.RuleType.DIGEST.equals(ruleType);
        return new AlertRuleRow(
                id,
                "p1",
                ruleType,
                "roll-up",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                digest ? cron : null,
                digest ? null : cron,
                true,
                snoozedUntil,
                null,
                null,
                null,
                "{}",
                created,
                created);
    }

    private static AlertEventRow firing(String id, String ruleType, String windowStart) {
        return new AlertEventRow(
                id,
                "p1",
                "r_ok",
                null,
                ruleType,
                null,
                AlertEventRow.State.FIRING,
                windowStart,
                windowStart,
                null,
                null,
                "{}",
                null,
                CREATED,
                CREATED);
    }
}

// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.hits.HitRule.Completeness;
import ai.tessary.endusers.hits.HitRule.Reach;
import ai.tessary.endusers.hits.HitRule.Rows;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The hit rule each detector gets (plan 3.2, hit rule table). */
class HitRulesTest {

    private final HitRules rules = new HitRules(List.of(new OpenHitSource()));

    @Test
    void unknownDetectorKind_usesDefaultRule_andIsNotComplete() {
        HitRule rule = rules.ruleFor(new HitFinding("paid_custom_rate", "custom_rate", false));

        assertAll(
                () -> assertEquals("default", rule.name()),
                () -> assertFalse(rule.complete(), "an unknown kind may not record every hit"),
                () -> assertEquals(Reach.MEMBER, rule.reach()),
                () -> assertEquals(Set.of("witness"), rule.hitRoles(Set.of("member", "witness"))),
                () -> assertEquals(
                        Set.of("member", "exemplar", "changepoint"),
                        rule.hitRoles(Set.of("member", "baseline")),
                        "with no witness the flagged roles are the hits"),
                () -> assertEquals(Set.of(), rule.hitRoles(Set.of("baseline"))));
    }

    @Test
    void frustrationAndToolError_hitIsWitness_notMember() {
        for (String kind : List.of("frustration", "tool_error")) {
            HitRule rule = rules.ruleFor(new HitFinding(kind, "rate", false));
            assertAll(
                    kind,
                    () -> assertEquals(Set.of("witness"), rule.hitRoles(Set.of("member", "witness"))),
                    () -> assertEquals(Set.of(), rule.hitRoles(Set.of("member")), "a member is a trial, not a hit"),
                    () -> assertEquals(Reach.MEMBER, rule.reach()),
                    () -> assertTrue(rule.complete()));
        }
    }

    @Test
    void groundedness_countsAnAnswerOncePerTrace() {
        HitRule rule = rules.ruleFor(new HitFinding("groundedness", "groundedness_rate", false));

        assertAll(() -> assertTrue(rule.onePerTrace()), () -> assertEquals(Rows.EVIDENCE, rule.rows()));
    }

    @Test
    void cappedDetectors_readTheirDetectionTables() {
        HitRule malformed = rules.ruleFor(new HitFinding("malformed_output", "malformed_rate", false));
        HitRule leak = rules.ruleFor(new HitFinding("secret_leak", "armed_window", true));

        assertAll(
                () -> assertEquals(Rows.MALFORMED_OUTPUT_DETECTIONS, malformed.rows()),
                () -> assertEquals(Rows.SECRET_LEAK_DETECTIONS, leak.rows()),
                () -> assertTrue(malformed.readsDetections() && leak.readsDetections()),
                () -> assertEquals(Reach.CALL_SITE, leak.reach()));
    }

    @Test
    void customPerSpanClassifier_membersOfTheArmedWindowAreHits() {
        HitRule whole = rules.ruleFor(new HitFinding("refund_promise", "armed_window", false));
        HitRule faceted = rules.ruleFor(new HitFinding("refund_promise", "armed_window", true));

        assertAll(
                () -> assertEquals(Set.of("member"), whole.hitRoles(Set.of("member"))),
                () -> assertEquals(Reach.CALL_SITE, whole.reach()),
                () -> assertTrue(whole.complete()),
                () -> assertFalse(whole.readsDetections()),
                () -> assertEquals(HitRule.DEFAULT, faceted, "a facet of an unknown classifier is an unknown kind"));
    }

    /**
     * The bug: a metric shift is not measured like every other issue, or counts its baseline window or change point
     * as hits.
     */
    @Test
    void metricDrift_membersOfTheShiftedWindowAreHits() {
        for (String kind : List.of("duration_drift", "cost_drift")) {
            HitRule rule = rules.ruleFor(new HitFinding(kind, "distribution_shift", false));
            assertAll(
                    kind,
                    () -> assertEquals(Rows.EVIDENCE, rule.rows()),
                    () -> assertEquals(Set.of("member"), rule.hitRoles(Set.of("member", "changepoint", "baseline"))),
                    () -> assertEquals(Reach.CALL_SITE, rule.reach()),
                    () -> assertEquals(Completeness.METRIC_SHIFT, rule.completeness()),
                    () -> assertTrue(rule.complete(), "the members are every trace of the window: none is missing"));
        }
    }

    @Test
    void anotherSourceAddsARule_forItsOwnKind() {
        HitRule paid = new HitRule(
                "paid_custom_rate",
                Rows.EVIDENCE,
                List.of(Set.of("witness")),
                false,
                Reach.MEMBER,
                Completeness.COMPLETE);
        HitRules withPaid = new HitRules(List.of(
                new OpenHitSource(),
                f -> "paid_custom_rate".equals(f.classifierKey()) ? Optional.of(paid) : Optional.empty()));

        assertAll(
                () -> assertEquals(paid, withPaid.ruleFor(new HitFinding("paid_custom_rate", "custom_rate", false))),
                () -> assertEquals(
                        OpenHitSource.TOOL_ERROR, withPaid.ruleFor(new HitFinding("tool_error", "rate_shift", false))));
    }

    @Test
    void twoSourcesWithARuleForOneFinding_failLoud() {
        HitRules twice = new HitRules(List.of(new OpenHitSource(), new OpenHitSource()));

        assertThrows(IllegalStateException.class, () -> twice.ruleFor(new HitFinding("tool_error", "rate", false)));
    }
}

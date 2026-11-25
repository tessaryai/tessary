// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.Estimate.Numbers;
import ai.tessary.endusers.judge.PopulationReading.Result;
import ai.tessary.endusers.judge.Verdicts.Decision;
import ai.tessary.endusers.judge.Verdicts.Input;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.TracerVerdict;
import ai.tessary.endusers.model.Verdict;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The verdict of spec 8.7 with the level 2 guards, first rule that applies. A rule out of order, a guard key that
 * switches more than its own rule, a claim that skips the null, the least size, the second contrast or the tracer, a
 * lower limit kept where no claim is made, or numbers on a verdict that shows none would print a loss the data does not
 * hold.
 */
class VerdictsTest {

    /** Spec 8.5 sample E5: compared 96 of 96, about 23 lost (11 to 35), with the null well under the rate. */
    private static LineageEstimate e5() {
        return estimate(0.24, 0.30, 0.05, 0.03, 96, 96, 50, 41, 50, 1000, null);
    }

    private static LineageEstimate estimate(
            double rateLo,
            double rateHi,
            double se,
            double s0,
            int nM,
            int nEst,
            double accEff,
            double sumLo,
            double sumHi,
            int controlIds,
            @Nullable LineageEstimate withoutAccounts) {
        return new LineageEstimate(
                nEst + 16,
                nEst,
                nM,
                rateLo,
                rateHi,
                se,
                0,
                s0,
                0.1,
                60,
                accEff,
                sumLo,
                sumHi,
                18,
                300,
                controlIds,
                8,
                8,
                Set.of(),
                withoutAccounts);
    }

    private static LineageHits hits(boolean complete, boolean alwaysPresent) {
        return new LineageHits(Map.of(1, List.of(100)), 100, 150, List.of(1), complete, alwaysPresent);
    }

    /** Every rule passes up to evidence of loss. */
    private static B base() {
        return new B();
    }

    /** A mutable builder: each step of a test changes one input. */
    static final class B {
        PageState page = PageState.OK;
        boolean groupBanner;
        Guards guards = Guards.allOn();

        @Nullable
        Integer horizon = 400;

        int idHistory = 119;
        int f = 30;
        boolean metricShift;
        boolean complete = true;
        boolean alwaysPresent;

        @Nullable
        LineageEstimate estimate = e5();

        double ageHit = 40;
        int tVis = 18;
        boolean clusters;

        @Nullable
        Result pop;

        TracerVerdict tracer = TracerVerdict.NO_CHANGE_SEEN;

        @Nullable
        LineageHits lineage;

        B with(Consumer<B> change) {
            B b = copy();
            change.accept(b);
            return b;
        }

        B copy() {
            B b = new B();
            b.page = page;
            b.groupBanner = groupBanner;
            b.guards = guards;
            b.horizon = horizon;
            b.idHistory = idHistory;
            b.f = f;
            b.metricShift = metricShift;
            b.complete = complete;
            b.alwaysPresent = alwaysPresent;
            b.estimate = estimate;
            b.ageHit = ageHit;
            b.tVis = tVis;
            b.clusters = clusters;
            b.pop = pop;
            b.tracer = tracer;
            b.lineage = lineage;
            return b;
        }

        Input input() {
            LineageHits h = lineage != null ? lineage : hits(complete, alwaysPresent);
            return new Input(
                    page,
                    groupBanner,
                    guards,
                    horizon,
                    idHistory,
                    f,
                    metricShift,
                    h,
                    estimate,
                    ageHit,
                    tVis,
                    clusters,
                    pop,
                    tracer);
        }

        Decision decide() {
            return Verdicts.decide(input());
        }
    }

    private static LineageEstimate nM(LineageEstimate e, int nM) {
        return estimate(
                e.rateLo(),
                e.rateHi(),
                e.se(),
                e.s0(),
                nM,
                e.nEst(),
                e.accEff(),
                e.sumLo(),
                e.sumHi(),
                e.controlIds(),
                e.withoutAccounts());
    }

    /** V1: the states from all rules applying to none; each step removes the condition of the rule that applied. */
    private static final List<B> CASCADE = List.of(
            base().with(b -> {
                b.page = PageState.TOO_EARLY;
                b.complete = false;
                b.horizon = 90;
                b.f = 42;
                b.ageHit = 10;
                b.estimate = nM(e5(), 17);
            }),
            base().with(b -> {
                b.complete = false;
                b.horizon = 90;
                b.f = 42;
                b.ageHit = 10;
                b.estimate = nM(e5(), 17);
            }),
            base().with(b -> {
                b.horizon = 90;
                b.f = 42;
                b.ageHit = 10;
                b.estimate = nM(e5(), 17);
            }),
            base().with(b -> {
                b.f = 42;
                b.ageHit = 10;
                b.estimate = nM(e5(), 17);
            }),
            base().with(b -> {
                b.ageHit = 10;
                b.estimate = nM(e5(), 17);
            }),
            base().with(b -> {
                b.ageHit = 18;
                b.estimate = nM(e5(), 17);
            }),
            base());

    private static final List<Verdict> CASCADE_VERDICTS = List.of(
            Verdict.PAGE_REFUSED,
            Verdict.HITS_NOT_COMPLETE,
            Verdict.HISTORY_TOO_SHORT,
            Verdict.CANNOT_TELL_YET,
            Verdict.CANNOT_MEASURE_YET,
            Verdict.TOO_FEW_IDS,
            Verdict.EVIDENCE_OF_LOSS);

    @Test
    void verdict_order() {
        for (int i = 0; i < CASCADE.size(); i++) {
            assertEquals(CASCADE_VERDICTS.get(i), CASCADE.get(i).decide().verdict(), "step " + i);
        }
        Decision tooFew = CASCADE.get(5).decide();
        Decision cannotMeasure = CASCADE.get(4).decide();
        Decision refused = CASCADE.get(0).decide();
        assertAll(
                () -> assertEquals(
                        Verdict.PAGE_REFUSED,
                        base().with(b -> {
                                    b.page = PageState.IDS_UNRELIABLE;
                                })
                                .decide()
                                .verdict()),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.page = PageState.PARTIAL_COVERAGE;
                                })
                                .decide()
                                .verdict(),
                        "partial coverage is not refused"),
                () -> assertEquals(
                        Verdict.HISTORY_TOO_SHORT,
                        base().with(b -> {
                                    b.idHistory = 41;
                                })
                                .decide()
                                .verdict(),
                        "under 42 days of user ID history"),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.idHistory = 42;
                                    b.horizon = 120;
                                })
                                .decide()
                                .verdict()),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.horizon = null;
                                })
                                .decide()
                                .verdict(),
                        "no horizon keeps facts for ever"),
                () -> assertEquals(
                        Verdict.TOO_FEW_IDS,
                        base().with(b -> {
                                    b.estimate = nM(e5(), 47);
                                })
                                .decide()
                                .verdict(),
                        "47 of 96 is under half"),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.estimate = nM(e5(), 48);
                                })
                                .decide()
                                .verdict()),
                () -> assertEquals(
                        Verdict.NOT_APPLICABLE,
                        base().with(b -> {
                                    b.metricShift = true;
                                })
                                .decide()
                                .verdict()),
                () -> assertFalse(tooFew.shown()),
                () -> assertEquals(17, tooFew.compared()),
                () -> assertNull(tooFew.lost()),
                () -> assertNull(tooFew.low()),
                () -> assertNull(tooFew.high()),
                () -> assertFalse(cannotMeasure.shown()),
                () -> assertNull(cannotMeasure.high(), "the upper limit is in the note only"),
                () -> assertEquals((0.30 + 2.6 * 0.05) * 96, cannotMeasure.upper(), 1e-9),
                () -> assertFalse(refused.shown()),
                () -> assertNull(refused.compared()),
                () -> assertNull(refused.upper()),
                () -> assertNull(refused.rankLost(), "the estimate was not reached"),
                () -> assertEquals(23.04, tooFew.rankLost(), 1e-9, "a hidden verdict ranks by the estimate"),
                () -> assertEquals(23.04, cannotMeasure.rankLost(), 1e-9));
    }

    @Test
    void noComparableHitId_isTooFewIds_withZero() {
        Decision d = base().with(b -> {
                    b.estimate = null;
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.TOO_FEW_IDS, d.verdict()),
                () -> assertEquals(0, d.compared()),
                () -> assertFalse(d.shown()),
                () -> assertNull(d.upper()),
                () -> assertNull(d.rankLost()));
    }

    static Stream<Arguments> guards() {
        return Stream.of(
                Arguments.of(
                        "hits-not-complete",
                        new Guards(true, true, true, false, true, true, true),
                        Verdict.HITS_NOT_COMPLETE),
                Arguments.of(
                        "history-too-short",
                        new Guards(true, true, false, true, true, true, true),
                        Verdict.HISTORY_TOO_SHORT),
                Arguments.of(
                        "cannot-tell-yet",
                        new Guards(true, false, true, true, true, true, true),
                        Verdict.CANNOT_TELL_YET));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guards")
    void eachGuardKeyOff_removesOnlyThatVerdictRule(String key, Guards off, Verdict rule) {
        for (int i = 0; i < CASCADE.size(); i++) {
            Verdict expected = CASCADE_VERDICTS.get(i) == rule ? CASCADE_VERDICTS.get(i + 1) : CASCADE_VERDICTS.get(i);
            Verdict got = CASCADE.get(i)
                    .with(b -> {
                        b.guards = off;
                    })
                    .decide()
                    .verdict();
            assertEquals(expected, got, key + " off, step " + i);
        }
    }

    @Test
    void evidenceOfLoss() {
        Decision d = base().decide();
        assertAll(
                () -> assertEquals(Verdict.EVIDENCE_OF_LOSS, d.verdict()),
                () -> assertTrue(d.shown()),
                () -> assertEquals(23.04, d.lost(), 1e-9, "23.04 >= max(3, 1.92)"),
                () -> assertEquals(10.56, d.low(), 1e-9),
                () -> assertEquals(41.28, d.high(), 1e-9),
                () -> assertEquals(2.6, d.z(), 1e-12),
                () -> assertEquals(41.0 / 50, d.visible(), 1e-12),
                () -> assertEquals(96, d.compared()),
                () -> assertFalse(d.contradicted()),
                () -> assertEquals(
                        Verdict.NOT_ENOUGH_EVIDENCE,
                        base().with(b -> {
                                    b.estimate = new LineageEstimate(
                                            112, 96, 96, 0.24, 0.30, 0.05, 0, 0.03, 0.24, 60, 50, 41, 50, 18, 300, 1000,
                                            8, 8, Set.of(), null);
                                })
                                .decide()
                                .verdict(),
                        "a rate not above the null's largest draw"),
                () -> assertEquals(
                        0.0,
                        base().with(b -> {
                                    b.tracer = TracerVerdict.SOME_IDS_REPLACED;
                                })
                                .decide()
                                .low(),
                        "some IDs are replaced: no claim"));
    }

    @Test
    void movedNotAdded() {
        Decision moved = base().with(b -> {
                    b.pop = new Result(2, 4.5, 11, 200, 900);
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.MOVED_NOT_ADDED, moved.verdict()),
                () -> assertTrue(moved.shown()),
                () -> assertEquals(0.0, moved.low()),
                () -> assertEquals(23.04, moved.lost(), 1e-9),
                () -> assertEquals(41.28, moved.high(), 1e-9),
                () -> assertTrue(moved.contradicted()),
                () -> assertEquals(11.0, moved.popHigh()),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.pop = new Result(2, 4.5, 11, 199, 900);
                                })
                                .decide()
                                .verdict(),
                        "199 trials before"),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.pop = new Result(2, 4.8, 11.6, 200, 900);
                                })
                                .decide()
                                .verdict(),
                        "11.6 is not under 0.5 x 23.04"));
    }

    @Test
    void leastSize() {
        Numbers two = new Numbers(0.05, 2.6, 0.24, 0.30, 2, 1, 41.28);
        Numbers three = new Numbers(0.05, 2.6, 0.24, 0.30, 3, 1, 41.28);
        Decision d = Verdicts.decide(base().input(), two);
        assertAll(
                () -> assertEquals(Verdict.NOT_ENOUGH_EVIDENCE, d.verdict()),
                () -> assertEquals(0.0, d.low(), "lost 2 of 96 makes no claim"),
                () -> assertEquals(2.0, d.lost()),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        Verdicts.decide(base().input(), three).verdict(),
                        "3 >= max(3, 1.92)"));
    }

    @Test
    void alwaysPresent() {
        Decision present = base().with(b -> {
                    b.lineage = firstEvidenceAfterT0(10);
                })
                .decide();
        Decision inside = base().with(b -> {
                    b.lineage = firstEvidenceAfterT0(20);
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.ALWAYS_PRESENT, present.verdict()),
                () -> assertTrue(present.shown()),
                () -> assertEquals(0.0, present.low()),
                () -> assertEquals(23.04, present.lost(), 1e-9),
                () -> assertEquals(41.28, present.high(), 1e-9),
                () -> assertEquals(Verdict.EVIDENCE_OF_LOSS, inside.verdict()));
    }

    /** The rule of {@link IssueHits}: one issue whose only evidence is {@code days} after {@code t0}. */
    private static LineageHits firstEvidenceAfterT0(int days) {
        int t0 = 50;
        IdDays none = new IdDays(
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new long[0],
                new int[0],
                new int[0][0],
                new long[0][0]);
        Facts facts = new Facts(
                200,
                t0,
                null,
                List.of(),
                none,
                List.of(),
                List.of(),
                List.of(),
                List.of(new IssueInput("L1", "C1", List.of("F1"), true, false)),
                List.of(new HitRow(0, 7, t0 + days, 1, Map.of(), 1)),
                new RouteClasses(Map.of(), false),
                List.of(),
                List.of(),
                List.of());
        HitSet set = IssueHits.build(facts, Map.of());
        return set.byLineage().get("L1");
    }

    @Test
    void fewAccounts() {
        LineageEstimate rerun = estimate(0.20, 0.26, 0.05, 0.03, 90, 96, 30, 30, 40, 1000, null);
        Decision few = base().with(b -> {
                    b.clusters = true;
                    b.estimate = estimate(0.24, 0.30, 0.05, 0.03, 96, 96, 4, 41, 50, 1000, rerun);
                })
                .decide();
        Decision wideRerun = base().with(b -> {
                    b.clusters = true;
                    b.estimate = estimate(
                            0.24,
                            0.30,
                            0.05,
                            0.03,
                            110,
                            120,
                            4,
                            41,
                            50,
                            1000,
                            estimate(0.20, 0.26, 0.05, 0.03, 105, 120, 25, 30, 40, 1000, null));
                })
                .decide();
        Decision blankRerun = base().with(b -> {
                    b.clusters = true;
                    b.estimate = estimate(0.24, 0.30, 0.05, 0.03, 96, 96, 4, 41, 50, 1000, null);
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.FEW_ACCOUNTS, few.verdict()),
                () -> assertTrue(few.shown()),
                () -> assertEquals(0.0, few.low()),
                () -> assertEquals(0.20 * 96, few.lost(), 1e-9, "the rerun without the account cell, by n_est"),
                () -> assertEquals((0.26 + 2.6 * 0.05) * 96, few.high(), 1e-9),
                () -> assertEquals(90, few.compared()),
                () -> assertEquals(30.0 / 40, few.visible(), 1e-12),
                () -> assertEquals(2.6, wideRerun.z(), 1e-12, "z reads the first estimate's 4 accounts"),
                () -> assertEquals(105, wideRerun.compared()),
                () -> assertEquals(Verdict.FEW_ACCOUNTS, blankRerun.verdict()),
                () -> assertEquals(23.04, blankRerun.lost(), 1e-9),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.estimate = estimate(0.24, 0.30, 0.05, 0.03, 96, 96, 4, 41, 50, 1000, null);
                                })
                                .decide()
                                .verdict(),
                        "no clusters"),
                () -> assertEquals(
                        Verdict.EVIDENCE_OF_LOSS,
                        base().with(b -> {
                                    b.clusters = true;
                                    b.estimate = estimate(0.24, 0.30, 0.05, 0.03, 96, 96, 5, 41, 50, 1000, null);
                                })
                                .decide()
                                .verdict(),
                        "5 accounts"));
    }

    private static LineageEstimate small(double rateHi, int nM, double sumLo) {
        return estimate(-0.04, rateHi, 0.03, 0.03, nM, 250, 50, sumLo, 20, 1000, null);
    }

    @Test
    void noMeasurableLoss() {
        Decision none = base().with(b -> {
                    b.estimate = small(-0.032, 200, 14);
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.NO_MEASURABLE_LOSS, none.verdict()),
                () -> assertTrue(none.shown()),
                () -> assertEquals(12.0, none.high(), 1e-9, "(-0.032 + 2 x 0.04) x 250 <= 12.5"),
                () -> assertEquals(0.0, none.lost()),
                () -> assertEquals(
                        Verdict.NOT_ENOUGH_EVIDENCE,
                        base().with(b -> {
                                    b.estimate = small(-0.032, 200, 14);
                                    b.groupBanner = true;
                                })
                                .decide()
                                .verdict(),
                        "group banner"),
                () -> assertEquals(
                        Verdict.NOT_ENOUGH_EVIDENCE,
                        base().with(b -> {
                                    b.estimate = small(-0.028, 200, 14);
                                })
                                .decide()
                                .verdict(),
                        "high 13"),
                () -> assertEquals(
                        Verdict.NOT_ENOUGH_EVIDENCE,
                        base().with(b -> {
                                    b.estimate = small(-0.032, 199, 14);
                                })
                                .decide()
                                .verdict(),
                        "n_m 199"),
                () -> assertEquals(
                        Verdict.NOT_ENOUGH_EVIDENCE,
                        base().with(b -> {
                                    b.estimate = small(-0.032, 200, 13.9);
                                })
                                .decide()
                                .verdict(),
                        "vis under 0.7"));
    }

    @Test
    void notComparable() {
        Decision d = base().with(b -> {
                    b.estimate = estimate(-0.11, -0.05, 0.05, 0.03, 200, 250, 50, 10, 20, 1000, null);
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.NOT_COMPARABLE, d.verdict(), "-0.11 <= -2 x 0.05"),
                () -> assertFalse(d.shown()),
                () -> assertNull(d.lost()),
                () -> assertNull(d.high()),
                () -> assertEquals(
                        Verdict.NOT_ENOUGH_EVIDENCE,
                        base().with(b -> {
                                    b.estimate = estimate(-0.09, -0.05, 0.05, 0.03, 200, 250, 50, 10, 20, 1000, null);
                                })
                                .decide()
                                .verdict()));
    }

    @Test
    void reachesNearlyAll() {
        LineageEstimate wide = estimate(0.24, 0.30, 0.05, 0.03, 96, 96, 50, 41, 50, 95, null);
        Decision noReading = base().with(b -> {
                    b.estimate = wide;
                })
                .decide();
        Decision reading = base().with(b -> {
                    b.estimate = wide;
                    b.pop = new Result(7.4, 3, 13.4, 300, 2000);
                })
                .decide();
        Decision negative = base().with(b -> {
                    b.estimate = wide;
                    b.pop = new Result(-3, 3, 3, 300, 2000);
                })
                .decide();
        assertAll(
                () -> assertEquals(Verdict.REACHES_NEARLY_ALL, noReading.verdict(), "95 control IDs, 96 compared"),
                () -> assertFalse(noReading.shown()),
                () -> assertNull(noReading.lost()),
                () -> assertEquals(Verdict.REACHES_NEARLY_ALL, reading.verdict()),
                () -> assertTrue(reading.shown()),
                () -> assertEquals(7.4, reading.lost(), 1e-12),
                () -> assertEquals(0.0, reading.low()),
                () -> assertEquals(13.4, reading.high(), 1e-12),
                () -> assertEquals(0.0, negative.lost()),
                () -> assertEquals(3.0, negative.high(), 1e-12),
                () -> assertEquals(23.04, noReading.rankLost(), 1e-9),
                () -> assertEquals(7.4, reading.rankLost(), 1e-12, "the shown number"));
    }

    @Test
    void noSymptomVerdict() {
        assertAll(
                () -> assertTrue(
                        Arrays.stream(Verdict.values()).noneMatch(v -> v.wire().startsWith("symptom")),
                        "the product has no declaration input"),
                () -> assertEquals(Verdict.EVIDENCE_OF_LOSS, base().decide().verdict(), "a frustration issue"));
    }

    @Test
    void ageHit_isTheMedianOverCoveredHitIds() {
        LineageHits hits = new LineageHits(
                Map.of(1, List.of(150, 160), 2, List.of(170), 3, List.of(190), 4, List.of(100)),
                100,
                190,
                List.of(1),
                true,
                false);
        assertAll(
                () -> assertEquals(30.0, Verdicts.ageHit(hits, Set.of(1, 2, 3), 200), "50, 30 and 10 days"),
                () -> assertEquals(40.0, Verdicts.ageHit(hits, Set.of(1, 2), 200)),
                () -> assertEquals(0.0, Verdicts.ageHit(hits, Set.of(), 200)));
    }

    @Test
    void tVis_isOfTheStratumMostCoveredHitIdsAreIn() {
        LineageHits hits = new LineageHits(
                Map.of(1, List.of(150), 2, List.of(150), 3, List.of(150), 4, List.of(150)),
                150,
                150,
                List.of(1),
                true,
                false);
        Map<Integer, IdOdds> byId = new HashMap<>();
        byId.put(1, odds(Stratum.K1));
        byId.put(2, odds(Stratum.K1));
        byId.put(3, odds(Stratum.E));
        byId.put(4, odds(Stratum.E));
        Map<Stratum, Integer> tVis = new TreeMap<>(Map.of(Stratum.K1, 9, Stratum.E, 18));
        StopOdds o = new StopOdds(byId, null, null, 30, List.of(), List.of(), null, null, Map.of(), tVis, Map.of());
        StopOdds noK2 = new StopOdds(
                Map.of(1, odds(Stratum.K2)),
                null,
                null,
                30,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                tVis,
                Map.of());
        assertAll(
                () -> assertEquals(9, Verdicts.tVis(hits, Set.of(1, 2, 3), o), "ID 4 is not covered"),
                () -> assertEquals(18, Verdicts.tVis(hits, Set.of(), o), "with none, stratum E"),
                () -> assertEquals(0, Verdicts.tVis(hits, Set.of(1), noK2), "K2 has no t_vis"));
    }

    @Test
    void usersHit_countsCoveredIds_andEveryIdOnARefusedPage() {
        LineageHits hits = new LineageHits(
                Map.of(1, List.of(150), 2, List.of(150), 3, List.of(150)), 150, 150, List.of(1), true, false);
        assertAll(
                () -> assertEquals(2, Verdicts.usersHit(hits, Set.of(1, 2), PageState.OK)),
                () -> assertEquals(2, Verdicts.usersHit(hits, Set.of(1, 2), PageState.PARTIAL_COVERAGE)),
                () -> assertEquals(3, Verdicts.usersHit(hits, Set.of(1, 2), PageState.TOO_EARLY)));
    }

    private static IdOdds odds(Stratum s) {
        return new IdOdds(s, 0.1, 0.1, 0.1, null, false, false, 3, 3);
    }
}

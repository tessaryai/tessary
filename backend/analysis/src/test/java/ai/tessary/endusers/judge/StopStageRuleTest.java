// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.StopStageRule.Decision;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Stratum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the Probably stopped stage may show, by the first rule of the level 2 withholding rule that applies, with the
 * "stops cannot be named yet" notice of spec 7.2. A rule out of order, a null past check read as a pass, a guard key that
 * switches more than its own rule, or a notice cut that is not {@code 1.5 * named + 20} shows a stop count the app's own
 * past cannot stand behind.
 */
class StopStageRuleTest {

    private static final Case BASE =
            new Case(400, 12, 0.9, 41, 0.1, new StageCounts(0, 0, 0, 0, 125, 141, 120, 160, 0));

    record Case(
            @Nullable Integer horizon,
            int anchors,
            @Nullable Double namedRight,
            int f,
            @Nullable Double oneDayShare,
            StageCounts counts) {

        Case horizon(@Nullable Integer v) {
            return new Case(v, anchors, namedRight, f, oneDayShare, counts);
        }

        Case anchors(int v) {
            return new Case(horizon, v, namedRight, f, oneDayShare, counts);
        }

        Case namedRight(@Nullable Double v) {
            return new Case(horizon, anchors, v, f, oneDayShare, counts);
        }

        Case f(int v) {
            return new Case(horizon, anchors, namedRight, v, oneDayShare, counts);
        }

        Case oneDayShare(@Nullable Double v) {
            return new Case(horizon, anchors, namedRight, f, v, counts);
        }

        Case counts(double expected, double low, double high, int named) {
            return new Case(
                    horizon,
                    anchors,
                    namedRight,
                    f,
                    oneDayShare,
                    new StageCounts(0, 0, 0, 0, named, expected, low, high, 0));
        }
    }

    record Row(String name, UnaryOperator<Case> change, StopStageState expected) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Row> ROWS = List.of(
            new Row("base", c -> c, StopStageState.EXPECTED_AND_NAMED),
            new Row("horizon 90", c -> c.horizon(90), StopStageState.HISTORY_TOO_SHORT),
            new Row("horizon 120", c -> c.horizon(120), StopStageState.EXPECTED_AND_NAMED),
            new Row("no horizon", c -> c.horizon(null), StopStageState.EXPECTED_AND_NAMED),
            new Row("3 anchors", c -> c.anchors(3), StopStageState.NOT_JUDGED_YET),
            new Row("named_right null", c -> c.namedRight(null), StopStageState.NOT_JUDGED_YET),
            new Row("F 42", c -> c.f(42), StopStageState.CANNOT_TELL_YET),
            new Row("one_day_share 0.30", c -> c.oneDayShare(0.30), StopStageState.MIXED_ID_FORMATS),
            new Row("no one-day share", c -> c.oneDayShare(null), StopStageState.EXPECTED_AND_NAMED),
            new Row("named_right 0.79", c -> c.namedRight(0.79), StopStageState.EXPECTED_ONLY),
            new Row("named_right 0.80", c -> c.namedRight(0.80), StopStageState.EXPECTED_AND_NAMED),
            new Row("horizon 90, 3 anchors", c -> c.horizon(90).anchors(3), StopStageState.HISTORY_TOO_SHORT),
            new Row("3 anchors, F 42", c -> c.anchors(3).f(42), StopStageState.NOT_JUDGED_YET),
            new Row("F 42, one_day_share 0.30", c -> c.f(42).oneDayShare(0.30), StopStageState.CANNOT_TELL_YET),
            new Row(
                    "one_day_share 0.30, named_right 0.79",
                    c -> c.oneDayShare(0.30).namedRight(0.79),
                    StopStageState.MIXED_ID_FORMATS));

    static Stream<Row> rows() {
        return ROWS.stream();
    }

    @ParameterizedTest
    @MethodSource("rows")
    void stopStage_table(Row row) {
        assertEquals(
                row.expected(), decide(Guards.allOn(), row.change().apply(BASE)).state());
    }

    static Stream<Arguments> guardsOff() {
        return Stream.of(
                Arguments.of(
                        "not-judged-without-past-check",
                        new Guards(false, true, true, true, true, true, true),
                        Map.of("named_right null", StopStageState.EXPECTED_ONLY)),
                Arguments.of(
                        "cannot-tell-yet",
                        new Guards(true, false, true, true, true, true, true),
                        Map.of(
                                "F 42", StopStageState.EXPECTED_AND_NAMED,
                                "F 42, one_day_share 0.30", StopStageState.MIXED_ID_FORMATS)),
                Arguments.of(
                        "history-too-short",
                        new Guards(true, true, false, true, true, true, true),
                        Map.of(
                                "horizon 90", StopStageState.EXPECTED_AND_NAMED,
                                "horizon 90, 3 anchors", StopStageState.NOT_JUDGED_YET)),
                Arguments.of(
                        "mixed-id-formats",
                        new Guards(true, true, true, true, false, true, true),
                        Map.of(
                                "one_day_share 0.30", StopStageState.EXPECTED_AND_NAMED,
                                "one_day_share 0.30, named_right 0.79", StopStageState.EXPECTED_ONLY)),
                Arguments.of("outage-banner", new Guards(true, true, true, true, true, true, false), Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardsOff")
    void eachGuardKeyOff_removesOnlyThatGuard(String key, Guards guards, Map<String, StopStageState> changed) {
        assertAll(ROWS.stream()
                .map(row -> () -> assertEquals(
                        changed.getOrDefault(row.name(), row.expected()),
                        decide(guards, row.change().apply(BASE)).state(),
                        row.name())));
    }

    @Test
    void cannotBeNamedYet() {
        assertAll(
                () -> assertEquals(
                        new Decision(
                                StopStageState.CANNOT_BE_NAMED_YET,
                                Map.of(
                                        "stopped_expected", 230.0,
                                        "stopped_low", 200.0,
                                        "stopped_high", 260.0,
                                        "stopped_named", 58.0,
                                        "plateau", 0.64)),
                        decide(Guards.allOn(), BASE.counts(230, 200, 260, 58)),
                        "230 >= 1.5 * 58 + 20 = 107"),
                () -> assertEquals(
                        StopStageState.EXPECTED_AND_NAMED,
                        decide(Guards.allOn(), BASE.counts(141, 120, 160, 125)).state(),
                        "141 < 1.5 * 125 + 20 = 207.5"),
                () -> assertEquals(
                        StopStageState.CANNOT_BE_NAMED_YET,
                        decide(Guards.allOn(), BASE.counts(107, 90, 124, 58)).state(),
                        "107 is the cut"),
                () -> assertEquals(
                        StopStageState.EXPECTED_AND_NAMED,
                        decide(Guards.allOn(), BASE.counts(106.9, 90, 124, 58)).state(),
                        "just under the cut"),
                () -> assertEquals(
                        0.5,
                        StopStageRule.decide(
                                        Guards.allOn(),
                                        400,
                                        new StopOdds(
                                                Map.of(),
                                                7.0,
                                                7.0,
                                                21,
                                                List.of(900, 907, 914, 921),
                                                List.of(),
                                                0.9,
                                                0.7,
                                                Map.of(Stratum.K1, 0.5),
                                                Map.of(),
                                                Map.of()),
                                        0.1,
                                        BASE.counts(230, 200, 260, 58).counts())
                                .numbers()
                                .get("plateau"),
                        "with no E curve the plateau of K1"));
    }

    @Test
    void decide_givesTheNumbersOfEachState() {
        assertAll(
                () -> assertEquals(
                        Map.of("horizon_days", 90.0, "horizon_days_needed", 120.0),
                        decide(Guards.allOn(), BASE.horizon(90)).numbers()),
                () -> assertEquals(
                        Map.of("anchors", 2.0, "anchors_needed", 4.0, "days_needed", 14.0),
                        decide(Guards.allOn(), BASE.anchors(2)).numbers(),
                        "one week-grid date a week"),
                () -> assertEquals(
                        Map.of("anchors", 12.0, "anchors_needed", 4.0),
                        decide(Guards.allOn(), BASE.namedRight(null)).numbers(),
                        "dates enough, but no record named"),
                () -> assertEquals(
                        Map.of("return_cycle_days", 42.0),
                        decide(Guards.allOn(), BASE.f(42)).numbers()),
                () -> assertEquals(
                        Map.of("one_day_share", 0.41),
                        decide(Guards.allOn(), BASE.oneDayShare(0.41)).numbers()),
                () -> assertEquals(
                        Map.of(
                                "stopped_expected", 141.0,
                                "stopped_low", 120.0,
                                "stopped_high", 160.0,
                                "named_right", 0.71,
                                "named_right_needed", 0.8),
                        decide(Guards.allOn(), BASE.namedRight(0.71)).numbers()),
                () -> assertEquals(
                        Map.of(
                                "stopped_expected", 141.0,
                                "stopped_low", 120.0,
                                "stopped_high", 160.0,
                                "named_right_needed", 0.8),
                        decide(new Guards(false, true, true, true, true, true, true), BASE.namedRight(null))
                                .numbers(),
                        "with the guard off and no past check"),
                () -> assertEquals(
                        Map.of(
                                "stopped_expected", 141.0,
                                "stopped_low", 120.0,
                                "stopped_high", 160.0,
                                "stopped_named", 125.0),
                        decide(Guards.allOn(), BASE).numbers()));
    }

    @Test
    void coveredOneDayShare_readsOnlyFormatsThatHoldACoveredId() {
        List<IdInfo> ids = List.of(info("a", 8), info("a", 8), info("b", 12), info("b", 12), info("c", 5));
        Map<Integer, IdIdentity> byId = Map.of(
                0, identity(null),
                1, identity(SetAsideReason.SERVICE),
                2, identity(SetAsideReason.SERVICE),
                3, identity(SetAsideReason.AGGREGATE),
                4, identity(null));
        assertAll(
                () -> assertEquals(
                        0.2,
                        StopStageRule.coveredOneDayShare(
                                facts(ids), identity(byId, Map.of("a/8", 0.2, "b/12", 0.5, "z/9", 0.9)))),
                () -> assertEquals(
                        0.35,
                        StopStageRule.coveredOneDayShare(
                                facts(ids), identity(byId, Map.of("a/8", 0.2, "b/12", 0.5, "c/5", 0.35))),
                        "the largest over covered formats"),
                () -> assertNull(
                        StopStageRule.coveredOneDayShare(facts(ids), identity(byId, Map.of("b/12", 0.5))),
                        "no covered format has a share"));
    }

    private static Decision decide(Guards guards, Case c) {
        List<Integer> anchors = new ArrayList<>(Collections.nCopies(c.anchors(), 0));
        for (int i = 0; i < anchors.size(); i++) anchors.set(i, 900 + 7 * i);
        StopOdds odds = new StopOdds(
                Map.of(),
                7.0,
                7.0,
                c.f(),
                anchors,
                List.of(),
                c.namedRight(),
                0.7,
                Map.of(Stratum.K1, 0.5, Stratum.E, 0.64),
                Map.of(),
                Map.of());
        return StopStageRule.decide(guards, c.horizon(), odds, c.oneDayShare(), c.counts());
    }

    private static IdInfo info(String prefix, int length) {
        return new IdInfo(prefix, length, null, 900, 990, 1, false);
    }

    private static IdIdentity identity(@Nullable SetAsideReason setAside) {
        return new IdIdentity(false, false, false, false, false, "(app)", null, setAside == null, setAside);
    }

    private static Identity identity(Map<Integer, IdIdentity> byId, Map<String, Double> oneDayShareByShape) {
        return new Identity(
                byId,
                Map.of(),
                Map.of(),
                new GroupStats(0, 0, null, null, null, null, false),
                oneDayShareByShape,
                1.0,
                0.0,
                0.0,
                1.0,
                1.0,
                0.0);
    }

    private static Facts facts(List<IdInfo> ids) {
        return new Facts(
                1000,
                800,
                null,
                ids,
                new IdDays(
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
                        new long[0][0]),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new RouteClasses(Map.of(), false),
                List.of(),
                List.of(),
                List.of());
    }
}

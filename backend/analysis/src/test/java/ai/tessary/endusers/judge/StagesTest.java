// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.TracerVerdict;
import ai.tessary.endusers.model.UserIdState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The funnel of board P1 from the v3 states (final-plan 3.3, decision Q7): Active holds active IDs with no substate and
 * new IDs; Silent, not judged holds overdue, held and unattended IDs; Probably stopped holds the other stopped IDs.
 * Set-aside IDs and IDs that cannot be followed are in no stage. A stage that drops held IDs or counts unattended stops
 * as named stops breaks the sum, and a stop range that ignores the ID-loss, held or tracer range prints a range that
 * misses the truth.
 */
class StagesTest {

    private static final int D = 1000;
    private static final List<Integer> OLD = List.of(900, 940, 980);
    private static final List<Integer> YOUNG = List.of(990, 995);

    @Test
    void stages() {
        Fixture f = new Fixture()
                .id(1, OLD, 0.1)
                .id(2, YOUNG, 0.1)
                .id(3, OLD, 0.4)
                .held(4, 0.2, 0.5)
                .id(5, OLD, 0.7, 0.7, 0.7, true, null)
                .id(6, OLD, 0.9)
                .id(7, OLD, 0.65)
                .id(8, OLD, 0.9, 0.9, 0.9, false, SetAsideReason.SERVICE)
                .id(9, OLD, 0.9);
        f.cannotFollow.add(9);
        f.censoredFrom.put(9, D - 10);
        Map<Integer, IdState> byId = f.states();
        double sd = 1.64 * Math.sqrt(0.09 + 0.09 + 0.24 + 0.16 + 0.21 + 0.09 + 0.2275);
        assertAll(
                () -> assertEquals(Stage.ACTIVE, byId.get(1).stage(), "active with no substate"),
                () -> assertEquals(Stage.ACTIVE, byId.get(2).stage(), "new"),
                () -> assertEquals(Stage.SILENT, byId.get(3).stage(), "overdue"),
                () -> assertEquals(Stage.SILENT, byId.get(4).stage(), "held"),
                () -> assertEquals(Stage.SILENT, byId.get(5).stage(), "grade unattended"),
                () -> assertEquals(Stage.STOPPED, byId.get(6).stage(), "stopped"),
                () -> assertEquals(Stage.STOPPED, byId.get(7).stage(), "probably stopped"),
                () -> assertEquals(null, byId.get(8).stage(), "set aside"),
                () -> assertEquals(null, byId.get(9).stage(), "cannot follow"),
                () -> assertEquals(
                        new StageCounts(2, 1, 3, 1, 2, 3.05, 3.05 - sd, 3.05 + sd, 7), f.counts(byId), "counts"));
    }

    @Test
    void stages_sampleOfBoardP1_addsUpToTheCountedIds() {
        Fixture f = new Fixture();
        int u = 0;
        for (int i = 0; i < 953; i++) f.id(u++, OLD, 0.1);
        for (int i = 0; i < 61; i++) f.id(u++, YOUNG, 0.1);
        for (int i = 0; i < 40; i++) f.id(u++, OLD, 0.4);
        for (int i = 0; i < 7; i++) f.held(u++, 0.2, 0.5);
        for (int i = 0; i < 125; i++) f.id(u++, OLD, 0.9);
        double expected = 953 * 0.1 + 61 * 0.1 + 40 * 0.4 + 7 * 0.2 + 125 * 0.9;
        double sd = 1.64 * Math.sqrt(953 * 0.09 + 61 * 0.09 + 40 * 0.24 + 7 * 0.16 + 125 * 0.09);
        StageCounts c = f.counts(f.states());
        assertAll(
                () -> assertEquals(1014, c.active()),
                () -> assertEquals(61, c.newIds()),
                () -> assertEquals(47, c.silent()),
                () -> assertEquals(7, c.held()),
                () -> assertEquals(125, c.stoppedNamed()),
                () -> assertEquals(1186, c.counted()),
                () -> assertEquals(c.counted(), c.active() + c.silent() + c.stoppedNamed(), "the stages add up"),
                () -> assertEquals(expected, c.stoppedExpected(), 1e-9),
                () -> assertEquals(expected - sd, c.stoppedLow(), 1e-9),
                () -> assertEquals(expected + sd, c.stoppedHigh(), 1e-9));
    }

    @Test
    void stage_table() {
        assertAll(
                () -> assertEquals(Stage.ACTIVE, Stages.stage(UserIdState.ACTIVE, null, null)),
                () -> assertEquals(Stage.ACTIVE, Stages.stage(UserIdState.NEW, null, null)),
                () -> assertEquals(Stage.SILENT, Stages.stage(UserIdState.ACTIVE, Substate.OVERDUE, null)),
                () -> assertEquals(Stage.SILENT, Stages.stage(UserIdState.ACTIVE, Substate.HELD, null)),
                () -> assertEquals(Stage.SILENT, Stages.stage(UserIdState.STOPPED, null, Grade.UNATTENDED)),
                () -> assertEquals(Stage.STOPPED, Stages.stage(UserIdState.STOPPED, null, Grade.STOPPED)),
                () -> assertEquals(Stage.STOPPED, Stages.stage(UserIdState.STOPPED, null, Grade.PROBABLY_STOPPED)),
                () -> assertEquals(Stage.STOPPED, Stages.stage(UserIdState.STOPPED, null, Grade.NOT_YET_CHECKED)));
    }

    @Test
    void stopRange_widensToTheIdLossRange() {
        Fixture f = new Fixture().id(1, OLD, 0.9, 0.9, 0.3, false, null).id(2, OLD, 0.9, 0.9, 0.3, false, null);
        f.idLoss = new IdLoss(List.of("r|1"), 950, false, 0.5, 0.75, Map.of(1, 1.0, 2, 1.0), 0, List.of(1), 0.3, 0.0);
        double sd = 1.64 * Math.sqrt(2 * 0.21);
        StageCounts c = f.counts(f.states());
        assertAll(
                () -> assertEquals(0.6, c.stoppedExpected(), 1e-12),
                () -> assertEquals(Math.max(0, 0.6 - sd), c.stoppedLow(), 1e-12),
                () -> assertEquals(1.8, c.stoppedHigh(), 1e-12, "the sum of p_stop before the ID-loss factor"));
    }

    @Test
    void stopRange_widensToTheHeldRange() {
        Fixture f = new Fixture();
        for (int u = 0; u < 10; u++) f.held(u, 0.1, 0.9);
        f.id(10, OLD, 0.9, 0.9, 0.9, false, SetAsideReason.SERVICE).member(10);
        f.id(11, OLD, 0.2).member(11);
        double sd = 1.64 * Math.sqrt(10 * 0.09 + 0.16);
        StageCounts c = f.counts(f.states());
        assertAll(
                () -> assertEquals(1.2, c.stoppedExpected(), 1e-12),
                () -> assertEquals(Math.max(0, 1.2 - sd), c.stoppedLow(), 1e-12),
                () -> assertEquals(
                        9.0 + 0.2,
                        c.stoppedHigh(),
                        1e-12,
                        "the calendar value tops the held range; a member with no calendar value counts its p_stop, a"
                                + " set-aside member nothing"));
    }

    @Test
    void stopRange_widensToTheTracerRange() {
        Fixture f = new Fixture();
        for (int u = 0; u < 10; u++) f.id(u, OLD, 0.9);
        f.replacedLow = 0.5;
        double sd = 1.64 * Math.sqrt(10 * 0.09);
        StageCounts c = f.counts(f.states());
        assertAll(
                () -> assertEquals(9.0, c.stoppedExpected(), 1e-12),
                () -> assertEquals(4.5, c.stoppedLow(), 1e-12, "the expected count times the replaced low end"),
                () -> assertEquals(9.0 + sd, c.stoppedHigh(), 1e-12));
    }

    private static final class Fixture {
        final Map<Integer, IdSeries> series = new TreeMap<>();
        final Map<Integer, IdIdentity> identity = new TreeMap<>();
        final Map<Integer, IdOdds> odds = new TreeMap<>();
        final List<Integer> heldMembers = new ArrayList<>();
        final Set<Integer> cannotFollow = new TreeSet<>();
        final Map<Integer, Integer> censoredFrom = new TreeMap<>();

        @Nullable
        IdLoss idLoss;

        @Nullable
        Double replacedLow;

        Fixture id(int u, List<Integer> days, double pStop) {
            return id(u, days, pStop, pStop, pStop, false, null);
        }

        Fixture member(int u) {
            heldMembers.add(u);
            return this;
        }

        Fixture held(int u, double pStop, double pStopCalendar) {
            heldMembers.add(u);
            id(u, OLD, pStop, pStop, pStop, false, null);
            IdOdds o = odds.get(u);
            odds.put(
                    u,
                    new IdOdds(
                            o.stratum(),
                            o.pNominal(),
                            o.pCalibrated(),
                            o.pStop(),
                            pStopCalendar,
                            false,
                            false,
                            o.daysSilent(),
                            o.clockSilence()));
            return this;
        }

        Fixture id(
                int u,
                List<Integer> days,
                double pNominal,
                double pCalibrated,
                double pStop,
                boolean unattended,
                @Nullable SetAsideReason setAside) {
            series.put(
                    u,
                    new IdSeries(days.getFirst(), days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null));
            identity.put(
                    u, new IdIdentity(false, false, false, false, false, "(app)", null, setAside == null, setAside));
            odds.put(
                    u,
                    new IdOdds(
                            Stratum.E,
                            pNominal,
                            pCalibrated,
                            pStop,
                            null,
                            unattended,
                            false,
                            D - days.getLast(),
                            D - days.getLast()));
            return this;
        }

        Map<Integer, IdState> states() {
            return States.byId(D, new Series(800, series), identity(), channel(), tracer(), odds(), false);
        }

        StageCounts counts(Map<Integer, IdState> byId) {
            return Stages.counts(byId, odds(), channel(), silence(), tracer());
        }

        Identity identity() {
            return new Identity(
                    identity,
                    Map.of(),
                    Map.of(),
                    new GroupStats(0, 0, null, null, null, null, false),
                    Map.of(),
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                    1.0,
                    0.0);
        }

        IdChannelResult channel() {
            return new IdChannelResult(idLoss, null, censoredFrom, cannotFollow, Set.of());
        }

        TracerResult tracer() {
            return replacedLow == null
                    ? new TracerResult(TracerVerdict.NO_CHANGE_SEEN, 10, 0, false, null, null, Set.of())
                    : new TracerResult(TracerVerdict.SOME_IDS_REPLACED, 30, 5, false, 900, replacedLow, Set.of());
        }

        SilenceResult silence() {
            if (heldMembers.isEmpty()) return new SilenceResult(List.of(), Map.of());
            SilenceEvent open = new SilenceEvent(
                    new Scope(ScopeKind.APP, null),
                    975,
                    null,
                    SilenceState.OPEN,
                    false,
                    100,
                    0.3,
                    0.1,
                    0.8,
                    0.8,
                    heldMembers,
                    D + 1);
            return new SilenceResult(List.of(open), Map.of());
        }

        StopOdds odds() {
            return new StopOdds(
                    odds,
                    7.0,
                    7.0,
                    21,
                    List.of(931, 938, 945, 952, 959, 966),
                    List.of(),
                    0.95,
                    0.9,
                    Map.of(Stratum.E, 0.9),
                    Map.of(),
                    Map.of());
        }
    }
}
